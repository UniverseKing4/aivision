package com.aivision.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aivision.app.databinding.ActivityMainBinding
import com.aivision.app.databinding.DialogApiKeyBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedImageUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    
    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                binding.imageView.setImageURI(uri)
                binding.placeholderText.visibility = View.GONE
                binding.analyzeButton.isEnabled = true
            }
        }
    }
    
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) selectImage() else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isDark = prefs.getBoolean("dark_mode", false)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (isDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES 
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        binding.selectButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                selectImage()
            } else {
                requestPermission.launch(if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        binding.analyzeButton.setOnClickListener { analyzeImage() }
        
        binding.copyButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("AI Analysis", binding.resultText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        updateThemeIcon(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_dark_mode -> {
                toggleDarkMode()
                invalidateOptionsMenu()
                true
            }
            R.id.action_settings -> {
                showApiKeyDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun updateThemeIcon(menu: Menu) {
        val currentMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = currentMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        menu.findItem(R.id.action_dark_mode)?.setIcon(if (isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode)
    }
    
    private fun toggleDarkMode() {
        val currentMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = currentMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        prefs.edit().putBoolean("dark_mode", !isDark).apply()
        
        val newMode = if (isDark) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        } else {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(newMode)
    }
    
    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImage.launch(intent)
    }
    
    private fun showApiKeyDialog() {
        val dialogBinding = DialogApiKeyBinding.inflate(layoutInflater)
        dialogBinding.apiKeyInput.setText(prefs.getString("api_key", ""))
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        
        dialogBinding.saveButton.setOnClickListener {
            val key = dialogBinding.apiKeyInput.text.toString()
            prefs.edit().putString("api_key", key).apply()
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        dialogBinding.cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
    
    private fun analyzeImage() {
        val apiKey = prefs.getString("api_key", "")
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.api_key_required), Toast.LENGTH_SHORT).show()
            showApiKeyDialog()
            return
        }
        
        selectedImageUri?.let { uri ->
            binding.progressBar.visibility = View.VISIBLE
            binding.analyzeButton.isEnabled = false
            binding.resultCard.visibility = View.GONE
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    val base64 = bitmapToBase64(bitmap)
                    val result = callPollinationsAPI(apiKey, base64)
                    
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.analyzeButton.isEnabled = true
                        binding.resultText.text = result
                        binding.resultCard.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.analyzeButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "${getString(R.string.error_occurred)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxSize = 1024
        val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
        val width = (bitmap.width * ratio).toInt()
        val height = (bitmap.height * ratio).toInt()
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        
        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun callPollinationsAPI(apiKey: String, base64Image: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        
        val json = JSONObject().apply {
            put("model", "openai")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Describe the image")
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
        }
        
        val request = Request.Builder()
            .url("https://gen.pollinations.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("API error: ${response.code}")
            val body = response.body?.string() ?: throw Exception("Empty response")
            val jsonResponse = JSONObject(body)
            return jsonResponse.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }
}
