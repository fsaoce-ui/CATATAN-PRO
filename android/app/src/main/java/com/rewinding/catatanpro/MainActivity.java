package com.rewinding.catatanpro;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.webkit.*;
import android.util.Log;

import com.google.android.gms.auth.api.signin.*;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int RC_GOOGLE = 701;
    private static final String TAG = "CatatanPro";
    private WebView webView;
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private GoogleSignInClient googleClient;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            FirebaseApp.initializeApp(this);
            auth = FirebaseAuth.getInstance();
            db = FirebaseFirestore.getInstance();
            FirebaseFirestoreSettings firestoreSettings =
                new FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(false)
                    .build();
            db.setFirestoreSettings(firestoreSettings);
            Log.i(TAG, "Firestore local persistence disabled to avoid SQLite lock conflicts.");
            int webClientId = getResources().getIdentifier("default_web_client_id", "string", getPackageName());
            if (webClientId == 0) throw new IllegalStateException("Konfigurasi Firebase default_web_client_id tidak ditemukan");
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(webClientId)).requestEmail().build();
            googleClient = GoogleSignIn.getClient(this, gso);

            webView = new WebView(this);
            setContentView(webView);
            WebSettings s = webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            webView.setWebViewClient(new WebViewClient());
            webView.setWebChromeClient(new WebChromeClient());
            webView.addJavascriptInterface(new Bridge(), "Android");
            webView.loadUrl("file:///android_asset/index.html");
        } catch (Exception e) {
            Log.e(TAG, "Gagal membuka aplikasi", e);
            finish();
        }
    }

    private void js(String script) {
        if (isFinishing() || isDestroyed() || webView == null) return;
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && webView != null)
                webView.evaluateJavascript(script, null);
        });
    }

    private void authError(String message) {
        js("window.onAuthError && window.onAuthError(" + JSONObject.quote(message == null ? "Terjadi kesalahan" : message) + ")");
    }

    private void authResult(Task<AuthResult> task) {
        if (task == null || !task.isSuccessful()) {
            Exception e = task == null ? null : task.getException();
            authError(e == null ? "Login gagal" : e.getMessage());
            return;
        }
        FirebaseUser u = auth == null ? null : auth.getCurrentUser();
        if (u == null) {
            authError("Login berhasil tetapi data akun tidak tersedia. Silakan coba lagi.");
            return;
        }
        try {
            JSONObject user = new JSONObject();
            user.put("uid", u.getUid());
            user.put("email", u.getEmail());
            user.put("name", u.getDisplayName() == null ? "" : u.getDisplayName());
            js("window.onAuthSuccess && window.onAuthSuccess(" + JSONObject.quote(user.toString()) + ")");
            loadRecords();
        } catch (Exception e) {
            Log.e(TAG, "Gagal memproses hasil login", e);
            authError(e.getMessage());
        }
    }

    private void loadRecords() {
        FirebaseUser u = auth == null ? null : auth.getCurrentUser();
        if (u == null || db == null) return;
        try {
            db.collection("users").document(u.getUid()).collection("records").get()
              .addOnSuccessListener(snapshot -> {
                  JSONArray arr = new JSONArray();
                  for (DocumentSnapshot doc : snapshot.getDocuments()) {
                      Object raw = doc.get("payload");
                      if (raw instanceof String) {
                          try { arr.put(new JSONObject((String) raw)); }
                          catch (Exception parseError) { Log.w(TAG, "Catatan cloud dilewati karena format tidak valid", parseError); }
                      }
                  }
                  js("window.onCloudRecords && window.onCloudRecords(" + JSONObject.quote(arr.toString()) + ")");
              })
              .addOnFailureListener(e -> {
                  Log.e(TAG, "Gagal memuat catatan cloud", e);
                  authError("Gagal mengambil catatan cloud: " + e.getMessage());
              });
        } catch (Exception e) {
            Log.e(TAG, "Kesalahan saat meminta catatan cloud", e);
            authError("Gagal mengambil catatan cloud: " + e.getMessage());
        }
    }

    private class Bridge {
        @JavascriptInterface public void login(String email, String password) {
            runOnUiThread(() -> auth.signInWithEmailAndPassword(email.trim(), password)
                .addOnCompleteListener(MainActivity.this, MainActivity.this::authResult));
        }
        @JavascriptInterface public void register(String email, String password) {
            runOnUiThread(() -> auth.createUserWithEmailAndPassword(email.trim(), password)
                .addOnCompleteListener(MainActivity.this, MainActivity.this::authResult));
        }
        @JavascriptInterface public void googleLogin() {
            runOnUiThread(() -> {
                if (googleClient != null && !isFinishing())
                    startActivityForResult(googleClient.getSignInIntent(), RC_GOOGLE);
                else authError("Layanan Google belum siap. Tutup lalu buka aplikasi kembali.");
            });
        }
        @JavascriptInterface public void logout() {
            runOnUiThread(() -> { if (auth != null) auth.signOut(); });
            js("window.onLoggedOut && window.onLoggedOut()");
        }
        @JavascriptInterface public void refresh() { loadRecords(); }
        @JavascriptInterface public void checkSession() {
            FirebaseUser u = auth == null ? null : auth.getCurrentUser();
            if (u == null) return;
            try {
                JSONObject user = new JSONObject();
                user.put("uid", u.getUid());
                user.put("email", u.getEmail());
                user.put("name", u.getDisplayName() == null ? "" : u.getDisplayName());
                js("window.onAuthSuccess && window.onAuthSuccess(" + JSONObject.quote(user.toString()) + ")");
                loadRecords();
            } catch (Exception e) {
                Log.e(TAG, "Gagal memeriksa sesi", e);
                authError(e.getMessage());
            }
        }
        @JavascriptInterface public void saveRecord(String json) {
            FirebaseUser u = auth == null ? null : auth.getCurrentUser();
            if (u == null) { authError("Silakan login terlebih dahulu"); return; }
            try {
                JSONObject obj = new JSONObject(json);
                String id = obj.optString("id", String.valueOf(System.currentTimeMillis()));
                java.util.Map<String,Object> payload = new java.util.HashMap<>();
                payload.put("payload", json);
                payload.put("updatedAt", FieldValue.serverTimestamp());
                db.collection("users").document(u.getUid()).collection("records").document(id)
                  .set(payload)
                  .addOnSuccessListener(v -> js("window.onCloudSave && window.onCloudSave(true)"))
                  .addOnFailureListener(e -> authError("Gagal menyimpan ke cloud: " + e.getMessage()));
            } catch (Exception e) {
                Log.e(TAG, "Gagal menyimpan catatan", e);
                authError(e.getMessage());
            }
        }
        @JavascriptInterface public void deleteRecord(String id) {
            FirebaseUser u = auth == null ? null : auth.getCurrentUser();
            if (u == null || db == null) return;
            db.collection("users").document(u.getUid()).collection("records").document(id).delete()
              .addOnFailureListener(e -> authError("Gagal menghapus dari cloud: " + e.getMessage()));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_GOOGLE) return;
        Log.i(TAG, "Google sign-in callback: resultCode=" + resultCode + ", dataNull=" + (data == null));
        if (data == null) {
            authError("Google tidak mengembalikan data akun. Silakan coba login kembali.");
            return;
        }
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
            if (account == null || account.getIdToken() == null) {
                authError("Token Google tidak diterima. Silakan coba login kembali.");
                return;
            }
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            auth.signInWithCredential(credential).addOnCompleteListener(this, this::authResult);
        } catch (ApiException e) {
            Log.e(TAG, "Google sign-in result error: " + e.getStatusCode(), e);
            authError("Login Google gagal: " + e.getStatusCode());
        } catch (Exception e) {
            Log.e(TAG, "Kesalahan saat kembali dari login Google", e);
            authError("Terjadi kesalahan saat kembali dari login Google: " + e.getMessage());
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}