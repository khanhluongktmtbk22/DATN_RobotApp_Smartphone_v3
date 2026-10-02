package com.example.datn_v1

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.example.datn_v1.databinding.FragmentProfileBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentProfileBinding.bind(view)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().getReference("Users")

        // Set email & tên NGAY LẬP TỨC từ FirebaseAuth (không chờ DB)
        binding.tvProfileEmail.text = auth.currentUser?.email ?: ""
        binding.tvProfileName.text = "Đang tải..."

        // Load tên từ Realtime Database
        loadUserProfile()

        binding.btnLogout.setOnClickListener { performLogout() }
    }

    private fun loadUserProfile() {
        val uid = auth.currentUser?.uid ?: return

        database.child(uid).get()
            .addOnSuccessListener { snapshot ->
                val b = _binding ?: return@addOnSuccessListener

                val user = snapshot.getValue(UserInfor::class.java)

                // Tên: ưu tiên fullName lưu lúc đăng ký trong DB
                b.tvProfileName.text = user?.fullName
                    ?.takeIf { it.isNotBlank() } ?: "Người dùng"

                // Email: nếu DB có thì hiện, fallback về Auth email (luôn có)
                val emailFromDb = user?.email?.takeIf { it.isNotBlank() }
                b.tvProfileEmail.text = emailFromDb ?: (auth.currentUser?.email ?: "")
            }
            .addOnFailureListener { e ->
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi tải thông tin: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun performLogout() {
        // Stop services that retain Firebase references for the current UID.
        ReminderService.stop(requireContext())
        TrackingService.stop(requireContext())
        auth.signOut()

        requireActivity().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            .edit { clear() }

        Toast.makeText(requireContext(), "Đã đăng xuất tài khoản", Toast.LENGTH_SHORT).show()

        val intent = Intent(requireContext(), LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
