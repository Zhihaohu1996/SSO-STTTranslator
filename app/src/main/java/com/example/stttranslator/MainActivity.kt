package com.example.stttranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import java.util.Locale
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException


private const val PREFS_NAME = "stt_prefs"
private const val KEY_ASKED_MIC_BEFORE = "asked_mic_before"

data class Lang(val label: String, val tag: String)
data class UserProfile(
    val name: String,
    val email: String
)

private val LANGS = listOf(
    Lang("Chinese (中文)", "zh"),
    Lang("English", "en"),
    Lang("Japanese (日本語)", "ja"),
    Lang("Korean (한국어)", "ko"),
    Lang("French (Français)", "fr"),
    Lang("Spanish (Español)", "es"),
    Lang("German (Deutsch)", "de"),
)

class MainActivity : ComponentActivity() {

    private lateinit var googleClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()

        googleClient = GoogleSignIn.getClient(this, gso)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    STTTranslateApp(googleClient = googleClient)
                }
            }
        }
    }
}

@Composable
private fun STTTranslateApp(googleClient: GoogleSignInClient) {
    val context = LocalContext.current

    var user by remember {
        mutableStateOf(GoogleSignIn.getLastSignedInAccount(context)?.toUserProfile())
    }
    var authStatus by remember { mutableStateOf("Please sign in to continue.") }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            user = account.toUserProfile()
            authStatus = "Signed in successfully."
        } catch (e: ApiException) {
            authStatus = "Google sign-in failed: code=${e.statusCode}"
        }
    }

    if (user == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("STT Translator")
            Text("This app requires Google account login before speech-to-text and translation can be used.")

            Button(
                onClick = { signInLauncher.launch(googleClient.signInIntent) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign in with Google")
            }

            Text("Status: $authStatus")
        }
        return
    }

    AuthenticatedSTTTranslateScreen(
        user = user!!,
        authStatus = authStatus,
        onSignOut = {
            googleClient.signOut().addOnCompleteListener {
                user = null
                authStatus = "Signed out."
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedSTTTranslateScreen(
    user: UserProfile,
    authStatus: String,
    onSignOut: () -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // ===== Permission =====麦克风权限状态
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var showRationaleDialog by remember { mutableStateOf(false) }
    var showGoSettingsDialog by remember { mutableStateOf(false) }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ===== STT State =====
    var isListening by remember { mutableStateOf(false) }
    var partialText by remember { mutableStateOf("") }
    var sourceText by remember { mutableStateOf("") }

    // ===== Translation State =====
    var translatedText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }

    // Target language selection
    var selectedLang by remember { mutableStateOf(LANGS.first()) }
    var langMenuExpanded by remember { mutableStateOf(false) }

    // ===== SpeechRecognizer =====
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }

    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 更稳定：减少 timeout/no match
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400)
        }
    }

    fun stopListeningSafe(cancel: Boolean) {
        runCatching { if (cancel) speechRecognizer.cancel() else speechRecognizer.stopListening() }
        isListening = false
    }

    fun startListeningSafe() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { speechRecognizer.cancel() }
        partialText = ""
        isListening = true
        runCatching { speechRecognizer.startListening(recognizerIntent) }
            .onFailure {
                isListening = false
                Toast.makeText(context, "Failed to start listening", Toast.LENGTH_SHORT).show()
            }
    }

    // ===== Permission Launcher =====
    val requestMicPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasMicPermission = granted
            if (granted) startListeningSafe()
            else {
                val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.RECORD_AUDIO
                )
                val askedBefore = prefs.getBoolean(KEY_ASKED_MIC_BEFORE, false)
                if (!shouldShow && askedBefore) showGoSettingsDialog = true
                else Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    fun requestMicPermissionOrStart() {
        if (hasMicPermission) {
            startListeningSafe()
            return
        }
        val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(
            activity, Manifest.permission.RECORD_AUDIO
        )
        val askedBefore = prefs.getBoolean(KEY_ASKED_MIC_BEFORE, false)

        when {
            shouldShow -> showRationaleDialog = true
            askedBefore -> showGoSettingsDialog = true
            else -> {
                prefs.edit().putBoolean(KEY_ASKED_MIC_BEFORE, true).apply()
                requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // ===== ML Kit translate =====
    fun translateWithMlKit(
        input: String,
        targetTag: String,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val identifier = LanguageIdentification.getClient()

        identifier.identifyLanguage(input)
            .addOnSuccessListener { langTag ->
                val sourceCode = TranslateLanguage.fromLanguageTag(langTag) ?: TranslateLanguage.ENGLISH
                val targetCode = TranslateLanguage.fromLanguageTag(targetTag)
                if (targetCode == null) {
                    runCatching { identifier.close() }
                    onError("Unsupported target language: $targetTag")
                    return@addOnSuccessListener
                }

                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceCode)
                        .setTargetLanguage(targetCode)
                        .build()
                )

                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        translator.translate(input)
                            .addOnSuccessListener { out ->
                                runCatching { translator.close() }
                                runCatching { identifier.close() }
                                onDone(out)
                            }
                            .addOnFailureListener { e ->
                                runCatching { translator.close() }
                                runCatching { identifier.close() }
                                onError("Translate failed: ${e.message}")
                            }
                    }
                    .addOnFailureListener { e ->
                        runCatching { translator.close() }
                        runCatching { identifier.close() }
                        onError("Model download failed: ${e.message}")
                    }
            }
            .addOnFailureListener { e ->
                runCatching { identifier.close() }
                onError("Language ID failed: ${e.message}")
            }
    }

    // ===== Listener =====
    DisposableEffect(Unit) {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { partialText = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                // 兜底：如果没有收到最终 onResults，就用 partial 当 final
                if (sourceText.isBlank() && partialText.isNotBlank()) {
                    sourceText = partialText
                    partialText = ""
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val matches =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                partialText = matches.firstOrNull().orEmpty()
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches =
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = matches.firstOrNull().orEmpty().trim()
                if (text.isNotBlank()) {
                    sourceText = text
                    partialText = ""
                    // 可选：你也可以在这里自动清空翻译
                    translatedText = ""
                }
            }

            override fun onError(error: Int) {
                isListening = false
                if (error == SpeechRecognizer.ERROR_CLIENT) return
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match (emulator may not capture audio)"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                    else -> "STT error: $error"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        })
        onDispose { runCatching { speechRecognizer.destroy() } }
    }

    // ===== UI =====
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("STT + Translate (Google Sign-In Required)")
        Text("Signed in as ${user.name}")
        Text(user.email)

        OutlinedButton(onClick = onSignOut) {
            Text("Sign out")
        }

        Text("Auth status: $authStatus")

        Spacer(modifier = Modifier.height(16.dp))
        Text("STT + Translate (Choose Target Language)", style = MaterialTheme.typography.titleLarge)

        if (partialText.isNotBlank()) {
            Text("Partial: $partialText", style = MaterialTheme.typography.bodySmall)
        }

        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it },
            label = { Text("Source text (from speech)") },
            modifier = Modifier.fillMaxWidth()
        )

        // Target language dropdown
        ExposedDropdownMenuBox(
            expanded = langMenuExpanded,
            onExpandedChange = { langMenuExpanded = !langMenuExpanded }
        ) {
            OutlinedTextField(
                value = selectedLang.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("Target language") },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = langMenuExpanded,
                onDismissRequest = { langMenuExpanded = false }
            ) {
                LANGS.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.label) },
                        onClick = {
                            selectedLang = lang
                            langMenuExpanded = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = translatedText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Translated output") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                if (isListening) stopListeningSafe(cancel = true)
                else requestMicPermissionOrStart()
            }) { Text(if (isListening) "Stop 🎤" else "Start 🎤") }

            OutlinedButton(
                enabled = sourceText.isNotBlank() && !isTranslating,
                onClick = {
                    isTranslating = true
                    translateWithMlKit(
                        input = sourceText,
                        targetTag = selectedLang.tag,
                        onDone = { out ->
                            translatedText = out
                            isTranslating = false
                        },
                        onError = { msg ->
                            translatedText = msg
                            isTranslating = false
                        }
                    )
                }
            ) { Text(if (isTranslating) "Translating..." else "Translate") }

            OutlinedButton(onClick = {
                stopListeningSafe(cancel = true)
                partialText = ""
                sourceText = ""
                translatedText = ""
            }) { Text("Reset") }
        }

        Text("Mic permission: ${if (hasMicPermission) "GRANTED" else "NOT GRANTED"}")
    }

    // ===== Dialogs =====
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            title = { Text("Microphone Permission Needed") },
            text = { Text("We need microphone access to convert your speech to text (STT).") },
            confirmButton = {
                TextButton(onClick = {
                    showRationaleDialog = false
                    prefs.edit().putBoolean(KEY_ASKED_MIC_BEFORE, true).apply()
                    requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showRationaleDialog = false }) { Text("Cancel") } }
        )
    }

    if (showGoSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showGoSettingsDialog = false },
            title = { Text("Permission Blocked") },
            text = { Text("Microphone permission is blocked. Please enable it in App Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showGoSettingsDialog = false
                    openAppSettings()
                }) { Text("Open Settings") }
            },
            dismissButton = { TextButton(onClick = { showGoSettingsDialog = false }) { Text("Cancel") } }
        )
    }
}

private fun Context.findActivity(): Activity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    error("Activity not found.")
}

private fun GoogleSignInAccount.toUserProfile(): UserProfile {
    return UserProfile(
        name = this.displayName ?: "Unknown",
        email = this.email ?: "Unknown"
    )
}