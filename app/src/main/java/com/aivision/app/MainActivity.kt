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
import androidx.appcompat.app.AppCompatDelegate
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
    private var selectedImageUris: MutableList<Uri> = mutableListOf()
    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private var timerJob: kotlinx.coroutines.Job? = null
    private var startTime: Long = 0
    private var analysisJob: kotlinx.coroutines.Job? = null
    
    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                selectedImageUris.clear()
                
                if (data.clipData != null) {
                    // Multiple images
                    val count = data.clipData!!.itemCount
                    for (i in 0 until count) {
                        selectedImageUris.add(data.clipData!!.getItemAt(i).uri)
                    }
                } else if (data.data != null) {
                    // Single image
                    selectedImageUris.add(data.data!!)
                }
                
                if (selectedImageUris.isNotEmpty()) {
                    binding.imageView.setImageURI(selectedImageUris[0])
                    binding.placeholderText.visibility = View.GONE
                    binding.imageCountText.visibility = View.VISIBLE
                    binding.imageCountText.text = "${selectedImageUris.size} image${if (selectedImageUris.size > 1) "s" else ""} selected"
                    binding.analyzeButton.isEnabled = true
                }
            }
        }
    }
    
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) selectImage() else Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        // Set proper background after splash
        window.setBackgroundDrawableResource(R.color.background)
        
        // Restore state
        savedInstanceState?.let {
            val imageUriStrings = it.getStringArrayList("imageUris")
            if (imageUriStrings != null && imageUriStrings.isNotEmpty()) {
                selectedImageUris = imageUriStrings.map { str -> Uri.parse(str) }.toMutableList()
                binding.imageView.setImageURI(selectedImageUris[0])
                binding.placeholderText.visibility = View.GONE
                binding.imageCountText.visibility = View.VISIBLE
                binding.imageCountText.text = "${selectedImageUris.size} image${if (selectedImageUris.size > 1) "s" else ""} selected"
                binding.analyzeButton.isEnabled = true
            }
            val resultText = it.getString("resultText")
            if (resultText != null) {
                binding.resultText.text = resultText
                binding.resultCard.visibility = View.VISIBLE
            }
            val isAnalyzing = it.getBoolean("isAnalyzing", false)
            if (isAnalyzing) {
                binding.progressBar.visibility = View.VISIBLE
                binding.timerText.visibility = View.VISIBLE
                binding.analyzeButton.isEnabled = false
                startTime = it.getLong("startTime", System.currentTimeMillis())
                startTimer()
                if (selectedImageUris.isNotEmpty()) {
                    resumeAnalysis(selectedImageUris)
                }
            }
        }
        
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
    
    override fun onSaveInstanceState(outState: android.os.Bundle) {
        super.onSaveInstanceState(outState)
        if (selectedImageUris.isNotEmpty()) {
            outState.putStringArrayList("imageUris", ArrayList(selectedImageUris.map { it.toString() }))
        }
        if (binding.resultCard.visibility == View.VISIBLE) {
            outState.putString("resultText", binding.resultText.text.toString())
        }
        if (binding.progressBar.visibility == View.VISIBLE) {
            outState.putBoolean("isAnalyzing", true)
            outState.putLong("startTime", startTime)
        }
    }
    
    private fun showCompletionSnackbar() {
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            "✓ Analysis complete!",
            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
        )
        val view = snackbar.view
        val params = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        params.gravity = android.view.Gravity.TOP
        params.topMargin = binding.toolbar.height + 16
        view.layoutParams = params
        snackbar.show()
    }
    
    private fun showBalanceSnackbar(balance: String) {
        val snackbar = com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            "Balance: $balance",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        )
        val view = snackbar.view
        val params = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        params.gravity = android.view.Gravity.TOP
        params.topMargin = binding.toolbar.height + 16
        view.layoutParams = params
        snackbar.show()
    }
    
    private fun showBalanceNotification(apiKey: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val balance = getApiBalance(apiKey)
                withContext(Dispatchers.Main) {
                    showBalanceSnackbar(balance)
                }
            } catch (e: Exception) {}
        }
    }
    
    private fun resumeAnalysis(uris: List<Uri>) {
        val apiKey = prefs.getString("api_key", "") ?: return
        
        analysisJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val customPrompt = binding.promptInput.text?.toString() ?: ""
                val result = analyzeMultipleImages(apiKey, uris, customPrompt)
                
                withContext(Dispatchers.Main) {
                    timerJob?.cancel()
                    binding.progressBar.visibility = View.GONE
                    binding.timerText.visibility = View.GONE
                    binding.analyzeButton.isEnabled = true
                    binding.resultText.text = result
                    binding.resultCard.visibility = View.VISIBLE
                    
                    showCompletionSnackbar()
                    
                    kotlinx.coroutines.delay(1500)
                    showBalanceNotification(apiKey)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    timerJob?.cancel()
                    binding.progressBar.visibility = View.GONE
                    binding.timerText.visibility = View.GONE
                    binding.analyzeButton.isEnabled = true
                }
            }
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
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(newMode)
    }
    
    private fun selectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
        
        if (selectedImageUris.isEmpty()) return
        
        binding.progressBar.visibility = View.VISIBLE
        binding.timerText.visibility = View.VISIBLE
        binding.analyzeButton.isEnabled = false
        binding.resultCard.visibility = View.GONE
        
        startTime = System.currentTimeMillis()
        startTimer()
        
        analysisJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val customPrompt = binding.promptInput.text?.toString() ?: ""
                val result = analyzeMultipleImages(apiKey, selectedImageUris, customPrompt)
                
                withContext(Dispatchers.Main) {
                    timerJob?.cancel()
                    binding.progressBar.visibility = View.GONE
                    binding.timerText.visibility = View.GONE
                    binding.analyzeButton.isEnabled = true
                    binding.resultText.text = result
                    binding.resultCard.visibility = View.VISIBLE
                    
                    val snackbar = com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        "✓ Analysis complete!",
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    )
                    val view = snackbar.view
                    val params = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                    params.gravity = android.view.Gravity.TOP
                    params.topMargin = binding.toolbar.height + 16
                    view.layoutParams = params
                    snackbar.show()
                    
                    kotlinx.coroutines.delay(1500)
                    showBalanceNotification(apiKey)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    timerJob?.cancel()
                    binding.progressBar.visibility = View.GONE
                    binding.timerText.visibility = View.GONE
                    binding.analyzeButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "${getString(R.string.error_occurred)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun startTimer() {
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                binding.timerText.text = formatTime(elapsed)
                kotlinx.coroutines.delay(100)
            }
        }
    }
    
    private fun formatTime(seconds: Double): String {
        return when {
            seconds < 60 -> String.format("%.1fs", seconds)
            else -> {
                val mins = (seconds / 60).toInt()
                val secs = seconds % 60
                String.format("%dm %.1fs", mins, secs)
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
    
    private fun analyzeMultipleImages(apiKey: String, uris: List<Uri>, customPrompt: String): String {
        if (uris.size == 1) {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uris[0])
            val base64 = bitmapToBase64(bitmap)
            return callPollinationsAPI(apiKey, base64, customPrompt)
        }
        
        val results = StringBuilder()
        uris.forEachIndexed { index, uri ->
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            val base64 = bitmapToBase64(bitmap)
            val prompt = if (customPrompt.isEmpty()) "Describe the image" else customPrompt
            val result = callPollinationsAPI(apiKey, base64, prompt)
            results.append("Image ${index + 1}:\n$result\n\n")
        }
        return results.toString().trim()
    }
    
    private fun callPollinationsAPI(apiKey: String, base64Image: String, customPrompt: String = ""): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        
        val prompt = customPrompt.ifEmpty { "Describe the image" }
        
        val json = JSONObject().apply {
            put("model", "openai")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
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
    
    private fun getApiBalance(apiKey: String): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        
        val request = Request.Builder()
            .url("https://gen.pollinations.ai/account/balance")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "Unknown"
            val body = response.body?.string() ?: return "Unknown"
            val jsonResponse = JSONObject(body)
            val balance = jsonResponse.optDouble("balance", -1.0)
            return if (balance >= 0) String.format("%.5f Pollen", balance) else "Active"
        }
    }
}
