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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
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
    companion object {
        private const val PROXY_URL = "https://ai-proxy.universeking.workers.dev"
    }
    
    private lateinit var binding: ActivityMainBinding
    private var selectedImageUris: MutableList<Uri> = mutableListOf()
    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private var timerJob: kotlinx.coroutines.Job? = null
    private var startTime: Long = 0
    private var analysisJob: kotlinx.coroutines.Job? = null
    private var originalButtonTint: android.content.res.ColorStateList? = null
    private val markwon by lazy { 
        io.noties.markwon.Markwon.builder(this)
            .usePlugin(io.noties.markwon.linkify.LinkifyPlugin.create())
            .build()
    }
    
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    
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
                    updateImageDisplay()
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
                updateImageDisplay()
            }
            val resultText = it.getString("resultText")
            if (resultText != null) {
                markwon.setMarkdown(binding.resultText, resultText)
                binding.resultCard.visibility = View.VISIBLE
            }
            val isAnalyzing = it.getBoolean("isAnalyzing", false)
            if (isAnalyzing) {
                setAnalyzingState()
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
        
        binding.analyzeButton.setOnClickListener { 
            if (binding.analyzeButton.text == "Stop") {
                stopAnalysis()
            } else {
                analyzeImage()
            }
        }
        
        binding.copyButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("AI Analysis", binding.resultText.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        
        var clearClickCount = 0
        binding.promptInputLayout.setEndIconOnClickListener {
            if (clearClickCount == 0) {
                clearClickCount = 1
                Toast.makeText(this, "Press again to clear prompt", Toast.LENGTH_SHORT).show()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    clearClickCount = 0
                }, 3000)
            } else {
                binding.promptInput.text?.clear()
                clearClickCount = 0
            }
        }
        
        binding.promptInputLayout.isEndIconVisible = false
        binding.promptInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.promptInputLayout.isEndIconVisible = !s.isNullOrEmpty()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
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
    
    private fun updateImageDisplay() {
        binding.placeholderText.visibility = View.GONE
        
        if (selectedImageUris.size == 1) {
            binding.imageView.visibility = View.VISIBLE
            binding.imageViewPager.visibility = View.GONE
            binding.imageCountText.visibility = View.GONE
            binding.imageView.setImageURI(selectedImageUris[0])
        } else {
            binding.imageView.visibility = View.GONE
            binding.imageViewPager.visibility = View.VISIBLE
            binding.imageCountText.visibility = View.VISIBLE
            binding.imageCountText.text = "${selectedImageUris.size} images"
            
            val adapter = ImagePagerAdapter(selectedImageUris)
            binding.imageViewPager.adapter = adapter
            binding.imageViewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    binding.imageCountText.text = "${position + 1}/${selectedImageUris.size}"
                }
            })
        }
        
        // Update button state based on image selection
        if (binding.analyzeButton.text != "Stop") {
            binding.analyzeButton.isEnabled = selectedImageUris.isNotEmpty()
        }
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
                    resetToIdleState()
                    markwon.setMarkdown(binding.resultText, result)
                    binding.resultCard.visibility = View.VISIBLE
                    
                    showCompletionSnackbar()
                    
                    // Only show balance for custom API keys
                    if (apiKey.isNotEmpty()) {
                        kotlinx.coroutines.delay(1500)
                        showBalanceNotification(apiKey)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    resetToIdleState()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resetToIdleState()
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
            R.id.action_model -> {
                showModelSelectionDialog()
                true
            }
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
        val savedKey = prefs.getString("api_key", "") ?: ""
        dialogBinding.apiKeyInput.setText(savedKey)
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        
        // Show clear button if there's existing text
        dialogBinding.apiKeyInputLayout.isEndIconVisible = savedKey.isNotEmpty()
        
        // Handle clear button visibility
        dialogBinding.apiKeyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dialogBinding.apiKeyInputLayout.isEndIconVisible = !s.isNullOrEmpty()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        
        var clearClickCount = 0
        dialogBinding.apiKeyInputLayout.setEndIconOnClickListener {
            if (clearClickCount == 0) {
                clearClickCount = 1
                Toast.makeText(this, "Press again to clear API key", Toast.LENGTH_SHORT).show()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    clearClickCount = 0
                }, 3000)
            } else {
                dialogBinding.apiKeyInput.text?.clear()
                clearClickCount = 0
            }
        }
        
        // Handle get API key link
        dialogBinding.getApiKeyLink.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://enter.pollinations.ai"))
            startActivity(intent)
        }
        
        dialogBinding.saveButton.setOnClickListener {
            val key = dialogBinding.apiKeyInput.text.toString()
            prefs.edit().putString("api_key", key).apply()
            Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        
        dialogBinding.cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
    
    private fun stopAnalysis() {
        analysisJob?.cancel()
        resetToIdleState()
    }
    
    private fun showModelSelectionDialog() {
        val savedModels = prefs.getString("synced_models", null)
        val (modelIds, modelNames) = if (savedModels != null) {
            parseSavedModels(savedModels)
        } else {
            getDefaultModels()
        }
        
        val currentModel = prefs.getString("model", "openai")
        val selectedIndex = modelIds.indexOf(currentModel)
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Select Model")
            .setSingleChoiceItems(modelNames, selectedIndex) { dialog, which ->
                if (modelIds[which] == "custom") {
                    dialog.dismiss()
                    showCustomModelDialog()
                } else {
                    prefs.edit().putString("model", modelIds[which]).apply()
                    Toast.makeText(this, modelNames[which], Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Sync") { dialog, _ ->
                dialog.dismiss()
                syncModelsFromAPI()
            }
            .create()
        
        dialog.show()
    }
    
    private fun getDefaultModels(): Pair<Array<String>, Array<String>> {
        val modelIds = arrayOf(
            "openai", "openai-fast", "gemini-fast", "claude-fast", "kimi", "polly", "custom"
        )
        val modelNames = arrayOf(
            "OpenAI GPT-5 Mini - Fast & Balanced",
            "OpenAI GPT-5 Nano - Ultra Fast & Affordable",
            "Google Gemini 2.5 Flash Lite - Ultra Fast & Cost-Effective",
            "Anthropic Claude Haiku 4.5 - Fast & Intelligent",
            "Moonshot Kimi K2.5 - Flagship Agentic Model with Vision & Multi-Agent",
            "Polly by @Itachi-1824 - Pollinations AI Assistant with GitHub, Code Search & Web Tools (Alpha)",
            "Custom Model"
        )
        return Pair(modelIds, modelNames)
    }
    
    private fun parseSavedModels(json: String): Pair<Array<String>, Array<String>> {
        try {
            val jsonArray = org.json.JSONArray(json)
            val ids = mutableListOf<String>()
            val names = mutableListOf<String>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                ids.add(obj.getString("id"))
                names.add(obj.getString("name"))
            }
            
            ids.add("custom")
            names.add("Custom Model")
            
            return Pair(ids.toTypedArray(), names.toTypedArray())
        } catch (e: Exception) {
            return getDefaultModels()
        }
    }
    
    private fun syncModelsFromAPI() {
        Toast.makeText(this, "Syncing vision models...", Toast.LENGTH_SHORT).show()
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://gen.pollinations.ai/models")
                    .get()
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val jsonArray = org.json.JSONArray(body)
                        val filteredModels = org.json.JSONArray()
                        
                        for (i in 0 until jsonArray.length()) {
                            val model = jsonArray.getJSONObject(i)
                            val inputMods = model.getJSONArray("input_modalities")
                            val outputMods = model.getJSONArray("output_modalities")
                            val paidOnly = model.optBoolean("paid_only", false)
                            
                            var hasImageInput = false
                            var hasTextOutput = false
                            
                            for (j in 0 until inputMods.length()) {
                                if (inputMods.getString(j) == "image") hasImageInput = true
                            }
                            for (j in 0 until outputMods.length()) {
                                if (outputMods.getString(j) == "text") hasTextOutput = true
                            }
                            
                            if (hasImageInput && hasTextOutput && !paidOnly) {
                                val filtered = org.json.JSONObject().apply {
                                    put("id", model.getString("name"))
                                    put("name", model.getString("description"))
                                }
                                filteredModels.put(filtered)
                            }
                        }
                        
                        prefs.edit().putString("synced_models", filteredModels.toString()).apply()
                        
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Synced ${filteredModels.length()} vision models", Toast.LENGTH_SHORT).show()
                            showModelSelectionDialog()
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Sync failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Sync error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showCustomModelDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Enter model name (e.g., openai)"
            setText(prefs.getString("custom_model", ""))
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Custom Model")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val customModel = input.text.toString().trim()
                if (customModel.isNotEmpty()) {
                    prefs.edit()
                        .putString("model", "custom")
                        .putString("custom_model", customModel)
                        .apply()
                    Toast.makeText(this, "Using custom model: $customModel", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun analyzeImage() {
        val apiKey = prefs.getString("api_key", "") ?: ""
        
        if (selectedImageUris.isEmpty()) return
        
        // Set analyzing state
        setAnalyzingState()
        
        startTime = System.currentTimeMillis()
        startTimer()
        
        analysisJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val customPrompt = binding.promptInput.text?.toString() ?: ""
                val result = analyzeMultipleImages(apiKey, selectedImageUris, customPrompt)
                
                withContext(Dispatchers.Main) {
                    resetToIdleState()
                    markwon.setMarkdown(binding.resultText, result)
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
                    
                    // Only show balance for custom API keys
                    if (apiKey.isNotEmpty()) {
                        kotlinx.coroutines.delay(1500)
                        showBalanceNotification(apiKey)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    resetToIdleState()
                    Toast.makeText(this@MainActivity, "Analysis stopped", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resetToIdleState()
                    Toast.makeText(this@MainActivity, "${getString(R.string.error_occurred)}: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun setAnalyzingState() {
        binding.progressBar.visibility = View.VISIBLE
        binding.timerText.visibility = View.VISIBLE
        binding.analyzeButton.text = "Stop"
        // Store original tint before changing it
        if (originalButtonTint == null) {
            originalButtonTint = binding.analyzeButton.backgroundTintList
        }
        // Use backgroundTint to change color while preserving Material Design styling
        binding.analyzeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, android.R.color.holo_red_light)
        )
        binding.analyzeButton.isEnabled = true
        binding.resultCard.visibility = View.GONE
    }
    
    private fun resetToIdleState() {
        timerJob?.cancel()
        binding.progressBar.visibility = View.GONE
        binding.timerText.visibility = View.GONE
        binding.analyzeButton.text = getString(R.string.analyze)
        // Restore original Material Design tint
        binding.analyzeButton.backgroundTintList = originalButtonTint
        binding.analyzeButton.isEnabled = selectedImageUris.isNotEmpty()
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
    
    private suspend fun analyzeMultipleImages(apiKey: String, uris: List<Uri>, customPrompt: String): String = withContext(Dispatchers.IO) {
        if (uris.size == 1) {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uris[0])
            val base64 = bitmapToBase64(bitmap)
            return@withContext callPollinationsAPI(apiKey, base64, customPrompt)
        }
        
        // Process all images in parallel
        val jobs = uris.mapIndexed { index, uri ->
            async {
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                val base64 = bitmapToBase64(bitmap)
                val prompt = if (customPrompt.isEmpty()) "Describe the image" else customPrompt
                val result = callPollinationsAPI(apiKey, base64, prompt)
                Pair(index, result)
            }
        }
        
        // Check for cancellation and cancel all jobs if needed
        try {
            val results = jobs.awaitAll()
            
            // Build formatted output
            val output = StringBuilder()
            results.sortedBy { it.first }.forEach { (index, result) ->
                output.append("**━━━ IMAGE ${index + 1} ━━━**\n\n")
                output.append(result)
                output.append("\n\n")
            }
            return@withContext output.toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            jobs.forEach { it.cancel() }
            throw e
        }
    }
    
    private fun callPollinationsAPI(apiKey: String, base64Image: String, customPrompt: String = ""): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        
        val prompt = customPrompt.ifEmpty { "Describe the image" }
        var model = prefs.getString("model", "openai") ?: "openai"
        
        if (model == "custom") {
            model = prefs.getString("custom_model", "openai") ?: "openai"
        }
        
        val json = JSONObject().apply {
            put("model", model)
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
        
        // Use proxy if no API key provided, otherwise use direct API
        val url = if (apiKey.isEmpty()) PROXY_URL else "https://gen.pollinations.ai/v1/chat/completions"
        
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
        
        // Only add Authorization header if using user's API key
        if (apiKey.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        
        client.newCall(requestBuilder.build()).execute().use { response ->
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
        // Skip balance check if using default proxy
        if (apiKey.isEmpty()) return "Using default API"
        
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
