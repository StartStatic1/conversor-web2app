package com.streamflix.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private View offlineLayout;

    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 5173;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreen();
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        offlineLayout = findViewById(R.id.offlineLayout);
        Button btnRetry = findViewById(R.id.btnRetry);

        setupWebView();
        setupBackPress();

        btnRetry.setOnClickListener(v -> {
            if (isOnline()) {
                offlineLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.reload();
            } else {
                Toast.makeText(this, "Ainda sem internet", Toast.LENGTH_SHORT).show();
            }
        });

        swipeRefresh.setOnRefreshListener(() -> {
            if (isOnline()) {
                webView.reload();
            } else {
                swipeRefresh.setRefreshing(false);
                Toast.makeText(this, "Sem internet", Toast.LENGTH_SHORT).show();
            }
        });

        if (savedInstanceState == null) {
            if (isOnline()) {
                webView.loadUrl(BuildConfig.SITE_URL);
            } else {
                showOffline();
            }
        }
    }

    // ✅ NOVO: tela cheia de verdade (sem barra de status/relógio/bateria em cima).
    // Usa a API moderna (WindowInsetsController) em vez das antigas flags
    // SYSTEM_UI_*, que estão depreciadas. Funciona em todas as versões do
    // Android suportadas (minSdk 21) porque é uma classe de compatibilidade.
    private void setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            // Se o usuário arrastar da borda, a barra aparece temporariamente e some sozinha de novo
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Reaplica a tela cheia sempre que a janela ganha foco de novo (ex: volta de
        // um popup de anúncio, do app switcher, ou de uma notificação) — sem isso o
        // Android costuma "devolver" a barra de status sozinho.
        if (hasFocus) {
            setupFullscreen();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        String ua = settings.getUserAgentString();
        if (!ua.contains("StreamFlixApp")) {
            settings.setUserAgentString(ua + " StreamFlixApp/1.0");
        }

        webView.setWebViewClient(new InternalWebViewClient());
        webView.setWebChromeClient(new InternalWebChromeClient());
        webView.setDownloadListener(new InternalDownloadListener());

        // ✅ NOVO: ponte JS <-> nativo. O site já checava "window.AndroidApp" pra
        // saber que está rodando dentro do app (em isWebView()), mas o nome nunca
        // tinha sido registrado no lado nativo — agora existe de verdade.
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidApp");

        // ✅ NOVO: só deixa o "puxar pra atualizar" iniciar quando a página estiver
        // bem no topo. Sem isso, qualquer arrasto vertical dentro de uma área com
        // overflow:hidden (modais/overlays como o MegaEmbed Plus) é interpretado
        // como pull-to-refresh, e o indicador fica "preso" na metade do caminho.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (swipeRefresh.isEnabled()) {
                    swipeRefresh.setEnabled(scrollY <= 0);
                }
            });
        }
    }

    // ✅ NOVO: interface exposta ao JS como "window.AndroidApp".
    // O site (megaembed.js / iptv-sync.js / app.js) chama
    // AndroidApp.setPullRefreshEnabled(false) ao abrir um overlay em tela cheia
    // (MegaEmbed Plus, IPTV, modais) e (true) ao fechar, travando o "arrastar"
    // nativo enquanto esses overlays estão abertos.
    private class WebAppInterface {
        @JavascriptInterface
        public void setPullRefreshEnabled(boolean enabled) {
            runOnUiThread(() -> swipeRefresh.setEnabled(enabled));
        }
    }

    private class InternalWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost() == null ? "" : uri.getHost();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();

            // Navegação dentro do próprio site: sempre carrega normal, nunca intercepta.
            if (host.contains(BuildConfig.SITE_HOST)) {
                return false;
            }

            // Esquemas que não são http/https (mailto:, tel:, whatsapp:, intent:, etc.)
            // sempre vão pro sistema lidar.
            if (!scheme.equals("http") && !scheme.equals("https")) {
                openExternally(uri);
                return true;
            }

            // ✅ CRÍTICO PRA ANÚNCIO FUNCIONAR: nunca intercepta navegação de
            // IFRAME (subframe). AdSense/Monetag carregam o anúncio dentro de um
            // iframe que costuma redirecionar internamente (pra registrar a
            // visualização/clique). Antes, esse redirecionamento interno do
            // iframe era tratado igual a uma navegação de tela inteira e
            // cancelado/jogado pro Chrome — isso quebrava o carregamento do
            // anúncio antes dele "ativar". Deixando passar (return false), o
            // iframe completa a navegação normalmente dentro da WebView.
            if (!request.isForMainFrame()) {
                return false;
            }

            // Daqui pra baixo é navegação de tela cheia pra um domínio fora do
            // site (ex: clique num link de anúncio, redirect de smart link).
            // Em vez de jogar pro navegador externo (o app perde foco, o usuário
            // sai do app, e fluxos de redirect de anúncio quebram no meio), abre
            // dentro do próprio app numa Activity separada — mesma ideia que já
            // era usada pros popups via window.open.
            Intent intent = new Intent(MainActivity.this, AdPopupActivity.class);
            intent.putExtra(AdPopupActivity.EXTRA_URL, uri.toString());
            startActivity(intent);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
            // ✅ NOVO: trava o "puxar" enquanto a página está de fato recarregando,
            // pra não dar pra arrastar de novo no meio da atualização e travar o
            // indicador visual.
            swipeRefresh.setEnabled(false);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
            swipeRefresh.setRefreshing(false);
            swipeRefresh.setEnabled(true);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                showOffline();
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            super.onReceivedSslError(view, handler, error);
        }
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Não foi possível abrir o link", Toast.LENGTH_SHORT).show();
        }
    }

    private class InternalWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progressBar.setProgress(newProgress);
            if (newProgress >= 100) {
                progressBar.setVisibility(View.GONE);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
            filePathCallback = callback;
            Intent intent = params.createIntent();
            try {
                startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView.HitTestResult result = view.getHitTestResult();
            String popupUrl = result != null ? result.getExtra() : null;

            if (popupUrl != null) {
                Intent intent = new Intent(MainActivity.this, AdPopupActivity.class);
                intent.putExtra(AdPopupActivity.EXTRA_URL, popupUrl);
                startActivity(intent);
                return false;
            }

            WebView transport = new WebView(MainActivity.this);
            transport.getSettings().setJavaScriptEnabled(true);
            transport.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    Intent intent = new Intent(MainActivity.this, AdPopupActivity.class);
                    intent.putExtra(AdPopupActivity.EXTRA_URL, request.getUrl().toString());
                    startActivity(intent);
                    return true;
                }
            });
            WebView.WebViewTransport transportObj = (WebView.WebViewTransport) resultMsg.obj;
            transportObj.setWebView(transport);
            resultMsg.sendToTarget();
            return true;
        }
    }

    private class InternalDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(MainActivity.this, "Não foi possível baixar o arquivo", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private void setupBackPress() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    moveTaskToBack(true);
                }
            }
        });
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void showOffline() {
        webView.setVisibility(View.GONE);
        offlineLayout.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        swipeRefresh.setEnabled(true);
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
