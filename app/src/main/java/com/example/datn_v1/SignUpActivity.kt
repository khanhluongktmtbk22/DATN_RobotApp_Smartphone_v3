package com.example.datn_v1

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
//import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
import com.example.datn_v1.databinding.ActivitySignupBinding
import com.google.firebase.auth.FirebaseAuth
//import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

@SuppressLint("StaticFieldLeak")
private lateinit var binding: ActivitySignupBinding


class SignUpActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
//        enableEdgeToEdge()
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        binding.btnRegister.setOnClickListener {
            performSignUp()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.tvLoginLink.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

    }

    private fun performSignUp() {
        val fullName = binding.edtFullName.text.toString().trim()
        val email = binding.edtEmailSignup.text.toString().trim()
        val password = binding.edtPasswordSignup.text.toString().trim()

        if (fullName.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Vui lòng điền đầy đủ thông tin", Toast.LENGTH_SHORT).show()
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val uid = auth.currentUser?.uid

                    // --- ĐÂY LÀ PHẦN LƯU DỮ LIỆU CÓ CẤU TRÚC ---
                    val user = UserInfor(uid, fullName, email)

                    uid?.let {
                        database.reference.child("Users").child(it).setValue(user)
                            .addOnSuccessListener {
                                sendVerificationEmail()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Lỗi lưu dữ liệu: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    }
                } else {
                    Toast.makeText(this, "Lỗi đăng ký: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun sendVerificationEmail() {
        auth.currentUser?.sendEmailVerification()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Đăng ký thành công! Hãy kiểm tra Email xác thực.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }


}