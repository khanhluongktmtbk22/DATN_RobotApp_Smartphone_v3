package com.example.datn_v1

import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.example.datn_v1.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.webrtc.VideoFrame

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    companion object {
        private const val TAG = "RobotMainActivity"
        private const val TAG_PROFILE  = "tab_profile"
        private const val TAG_TRACKING = "tab_tracking"
        private const val TAG_CHATBOT  = "tab_chatbot"
        private const val TAG_VIDEO    = "tab_video_call"
    }

    private var profileFragment: ProfileFragment? = null
    private var trackingFragment: TrackingFragment? = null
    private var chatbotFragment: ChatbotFragment? = null
    private var videoCallFragment: VideoCallFragment? = null
    private var currentActiveFragment: Fragment? = null

    // Firebase listener de tu dong chuyen sang VideoCallFragment khi Caregiver goi
    private lateinit var callRef: DatabaseReference
    private var callNodeListener: ValueEventListener? = null
    private var isVideoCallShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initTabs(savedInstanceState)
        setupNavigation()
        setupDrawer()
        listenForCaregiverCall()
        startBackgroundServices()
    }

    private fun startBackgroundServices() {
        ReminderService.start(this)
        TrackingService.start(this)
    }

    /**
     * Khởi tạo 3 tab chính (Profile, Tracking, Chatbot) và giữ chúng hoạt động song song.
     * TrackingFragment luôn chạy ngầm để phát hiện té ngã (LSTM) và bám theo người (YOLO).
     */
    private fun initTabs(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            profileFragment  = ProfileFragment()
            trackingFragment = TrackingFragment()
            chatbotFragment  = ChatbotFragment()

            fm.beginTransaction()
                .add(R.id.content_frame, profileFragment!!, TAG_PROFILE)
                .hide(profileFragment!!)
                .add(R.id.content_frame, trackingFragment!!, TAG_TRACKING)
                .hide(trackingFragment!!)
                .add(R.id.content_frame, chatbotFragment!!, TAG_CHATBOT)
                .commit()

            currentActiveFragment = chatbotFragment
            updateSidebarUI(binding.btnNavChatbot)
        } else {
            profileFragment  = fm.findFragmentByTag(TAG_PROFILE) as? ProfileFragment
            trackingFragment = fm.findFragmentByTag(TAG_TRACKING) as? TrackingFragment
            chatbotFragment  = fm.findFragmentByTag(TAG_CHATBOT) as? ChatbotFragment
            videoCallFragment = fm.findFragmentByTag(TAG_VIDEO) as? VideoCallFragment
            currentActiveFragment = chatbotFragment
        }
    }

    private fun showTab(targetFragment: Fragment, navButton: LinearLayout) {
        if (currentActiveFragment == targetFragment && targetFragment !is VideoCallFragment) {
            closeNavigationDrawer()
            return
        }

        // Nếu đang trong Video Call mà chuyển tab khác → đóng video call
        if (currentActiveFragment is VideoCallFragment) {
            removeVideoCallFragment()
            // WebRTC vừa nhả camera; khởi động lại CameraX cho tracking/AI.
            trackingFragment?.resumeCamera()
        }

        val transaction = supportFragmentManager.beginTransaction()
        currentActiveFragment?.let { transaction.hide(it) }
        transaction.show(targetFragment).commit()

        currentActiveFragment = targetFragment
        updateSidebarUI(navButton)
        closeNavigationDrawer()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::callRef.isInitialized) callNodeListener?.let { callRef.removeEventListener(it) }
    }

    /**
     * Lắng nghe Firebase: khi Caregiver bắt đầu gọi (callerRole=caregiver, status=calling)
     * thì tự động chuyển sang VideoCallFragment bất kể đang ở màn hình nào.
     */
    private fun listenForCaregiverCall() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        callRef = FirebaseDatabase.getInstance().getReference(FirebasePairPaths.activeCall(uid))
        callNodeListener = callRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status     = snapshot.child("status").getValue(String::class.java)
                val callerRole = snapshot.child("callerRole").getValue(String::class.java)

                if (status == "calling" && callerRole == "caregiver" && !isVideoCallShowing) {
                    isVideoCallShowing = true
                    runOnUiThread {
                        navigateToVideoCall()
                    }
                }

                // Đặt lại flag khi cuộc gọi kết thúc
                if (status == "ended" || status == null || !snapshot.exists()) {
                    isVideoCallShowing = false
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    fun navigateToVideoCall() {
        closeNavigationDrawer()
        if (currentActiveFragment is VideoCallFragment) return

        // Tạm dừng CameraX của Tracking để WebRTC sử dụng camera độc quyền
        trackingFragment?.pauseCamera()

        val fm = supportFragmentManager
        val existingVideo = fm.findFragmentByTag(TAG_VIDEO)
        if (existingVideo != null) {
            fm.beginTransaction().remove(existingVideo).commitNow()
        }

        val videoFrag = VideoCallFragment()
        videoCallFragment = videoFrag

        val transaction = fm.beginTransaction()
        currentActiveFragment?.let { transaction.hide(it) }
        transaction.add(R.id.content_frame, videoFrag, TAG_VIDEO).commit()

        currentActiveFragment = videoFrag
        updateSidebarUI(binding.btnNavVideo)
    }

    /**
     * Được gọi từ ChatbotFragment khi phát hiện lệnh gọi điện.
     * Navigate sang VideoCallFragment với flag from_chatbot=true.
     */
    fun navigateToVideoCallFromChatbot(emergencyReason: String? = null) {
        closeNavigationDrawer()
        if (currentActiveFragment is VideoCallFragment) return

        trackingFragment?.pauseCamera()

        val fm = supportFragmentManager
        val existingVideo = fm.findFragmentByTag(TAG_VIDEO)
        if (existingVideo != null) {
            fm.beginTransaction().remove(existingVideo).commitNow()
        }

        val videoFrag = VideoCallFragment().apply {
            arguments = Bundle().apply {
                putBoolean("from_chatbot", true)
                emergencyReason?.let { putString("emergency_reason", it) }
            }
        }
        videoCallFragment = videoFrag

        val transaction = fm.beginTransaction()
        currentActiveFragment?.let { transaction.hide(it) }
        transaction.add(R.id.content_frame, videoFrag, TAG_VIDEO).commit()

        currentActiveFragment = videoFrag
        updateSidebarUI(binding.btnNavVideo)
    }

    /**
     * WebRTC là camera owner trong cuộc gọi; chuyển cùng frame sang YOLO để
     * auto-follow/fall detection vẫn chạy mà không mở camera lần thứ hai.
     */
    fun submitWebRtcFrameForTracking(frame: VideoFrame) {
        trackingFragment?.submitWebRtcFrame(frame)
    }

    /**
     * Được gọi từ VideoCallFragment khi cuộc gọi kết thúc.
     */
    fun returnToChatbotAfterCall() {
        removeVideoCallFragment()
        // Khôi phục camera cho tracking chạy ngầm
        trackingFragment?.resumeCamera()

        // Quay lại ChatbotFragment
        chatbotFragment?.let {
            supportFragmentManager.beginTransaction().show(it).commit()
            currentActiveFragment = it
            it.onReturnFromCall()
        }
        updateSidebarUI(binding.btnNavChatbot)
    }

    private fun removeVideoCallFragment() {
        val fm = supportFragmentManager
        val fragment = videoCallFragment ?: fm.findFragmentByTag(TAG_VIDEO)
        if (fragment != null && fragment.isAdded) {
            // Đợi onDestroyView() dừng WebRTC capturer trước khi CameraX mở lại.
            fm.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
        if (currentActiveFragment === fragment) currentActiveFragment = null
        videoCallFragment = null
        isVideoCallShowing = false
    }

    private fun setupNavigation() {
        binding.btnNavProfile.setOnClickListener {
            profileFragment?.let { showTab(it, binding.btnNavProfile) }
        }

        binding.btnNavTracking.setOnClickListener {
            trackingFragment?.let { showTab(it, binding.btnNavTracking) }
        }

        binding.btnNavChatbot.setOnClickListener {
            chatbotFragment?.let { showTab(it, binding.btnNavChatbot) }
        }

        binding.btnNavVideo.setOnClickListener {
            navigateToVideoCall()
        }
    }

    /**
     * Drawer mặc định đóng hoàn toàn. Người dùng vuốt từ mép trái sang phải để mở;
     * nút Back sẽ đóng drawer trước khi thoát màn hình.
     */
    private fun setupDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    closeNavigationDrawer()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun closeNavigationDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun replaceFragment(fragment: Fragment) {
        when (fragment) {
            is ProfileFragment  -> profileFragment?.let { showTab(it, binding.btnNavProfile) }
            is TrackingFragment -> trackingFragment?.let { showTab(it, binding.btnNavTracking) }
            is ChatbotFragment  -> chatbotFragment?.let { showTab(it, binding.btnNavChatbot) }
            is VideoCallFragment -> navigateToVideoCall()
            else -> {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.content_frame, fragment)
                    .commit()
            }
        }
    }

    private fun updateSidebarUI(selectedItem: LinearLayout) {
        val menuItems = listOf(
            binding.btnNavProfile, binding.btnNavTracking,
            binding.btnNavVideo, binding.btnNavChatbot
        )
        val selectedColor = ContextCompat.getColor(this, R.color.color_sidebar_selected)
        menuItems.forEach { item ->
            if (item == selectedItem) {
                item.setBackgroundColor(selectedColor)
                item.alpha = 1.0f
            } else {
                item.setBackgroundColor(0)
                item.alpha = 0.6f
            }
        }
    }
}
