package com.example.datn_v1

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
//import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
import com.example.datn_v1.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth

private const val PREFS_NAME = "LoginPrefs"
private const val KEY_REMEMBER = "isRemembered"
private const val KEY_EMAIL = "savedEmail"

@SuppressLint("StaticFieldLeak")
private lateinit var binding: ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. Khởi tạo View Binding
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. Khởi tạo Firebase Auth
        auth = FirebaseAuth.getInstance()

        val sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isRemembered = sharedPref.getBoolean(KEY_REMEMBER, false)

        if (isRemembered) {
            val currentUser = auth.currentUser
            // Nếu Firebase vẫn còn phiên đăng nhập và đã xác thực email
            if (currentUser != null && currentUser.isEmailVerified) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                // Nếu phiên hết hạn, điền sẵn Email để người dùng chỉ cần nhập Pass
                val savedEmail = sharedPref.getString(KEY_EMAIL, "")
                binding.etEmail.setText(savedEmail)
                binding.cbRememberMe.isChecked = true
            }
        }

        // 3. Xử lý sự kiện chuyển sang trang Đăng ký (SignUp)
        binding.tvSignUpLink.setOnClickListener {
            val intent = Intent(this, SignUpActivity::class.java)
            startActivity(intent)
        }

        // 4. Xử lý nút Đăng nhập
        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        // 5. Xử lý Quên mật khẩu
        binding.tvForgotPassword.setOnClickListener {
            resetPassword()
        }
    }

    private fun performLogin() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập đầy đủ Email và Mật khẩu", Toast.LENGTH_SHORT).show()
            return
        }

        // Tiến hành đăng nhập vào Firebase
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser

                    // KIỂM TRA XÁC THỰC EMAIL
                    if (user?.isEmailVerified == true) {
                        Toast.makeText(this, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                        // Lưu trạng thái đã đăng nhập và email vào SharedPreferences
                        handleRememberMe(email)

                        // Chuyển vào màn hình chính (Dashboard/Monitoring)
                        val intent = Intent(this, MainActivity::class.java)
                        startActivity(intent)
                        finish() // Đóng màn hình Login
                    } else {
                        // Nếu chưa xác thực mail, không cho vào app
                        Toast.makeText(this, "Vui lòng xác thực Email trước khi đăng nhập!", Toast.LENGTH_LONG).show()
                        auth.signOut() // Đăng xuất để yêu cầu xác thực lại
                    }
                } else {
                    // Hiển thị lỗi đăng nhập (Sai pass, Email không tồn tại...)
                    Toast.makeText(this, "Đăng nhập thất bại: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun handleRememberMe(email: String) {
        val sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        sharedPref.edit {

            if (binding.cbRememberMe.isChecked) {
                putBoolean(KEY_REMEMBER, true)
                putString(KEY_EMAIL, email)
            } else {
                // Nếu người dùng không tích chọn, xóa dữ liệu cũ để bảo mật
                clear()
            }
        }
    }

    private fun resetPassword() {
        val email = binding.etEmail.text.toString().trim()
        if (email.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập Email để lấy lại mật khẩu", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Yêu cầu đã được gửi. Hãy kiểm tra Email của bạn!", Toast.LENGTH_LONG).show()
            }
        }
    }

}