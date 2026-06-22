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
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * Activity única que carrega o site em um WebView "nativo".
 *
 * Pontos importantes resolvidos aqui (motivo de não usar PWA/TWA da Microsoft/Google):
 *  1) Anúncios do AdSense não quebram o app: links de anúncio/redirecionamento e
 *     popups (window.open) são interceptados e abertos por fora (navegador externo
 *     ou Activity auxiliar), nunca derrubando a WebView principal.
 *  2) Botão voltar do Android não fecha nem recarrega o app: ele navega no
 *     histórico interno da WebView (goBack) e só fecha o app quando não há mais
 *     histórico.
 *  3) Rotação de tela NÃO recria a Activity nem recarrega a página, porque o
 *     AndroidManifest já declara configChanges para orientação/tamanho de tela —
 *     a WebView simplesmente é redimensionada, sem reload.
 *  4) Cookies de terceiros habilitados e DOM storage ligado, requisito básico
 *     para o AdSense funcionar corretamente dentro de uma WebView.
 */
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
        setupImmersiveMode();
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

        // Desativado: o gesto de puxar pra baixo estava conflitando com o
        // scroll de listas dentro do site (ex: lista de episódios em séries).
        swipeRefresh.setEnabled(false);

        // Só carrega a URL na primeira criação. Como a Activity não é recriada em
        // rotação (configChanges no Manifest), isto roda exatamente UMA vez por
        // sessão do app — é o que impede o reload ao girar a tela.
        if (savedInstanceState == null) {
            if (isOnline()) {
                webView.loadUrl(BuildConfig.SITE_URL);
            } else {
                showOffline();
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
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

        // Necessário para o AdSense conseguir abrir popups/novas janelas (window.open)
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);

        // Cookies de terceiros são exigidos pelo AdSense para medição/segmentação
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // User-Agent: mantém um UA "de navegador normal", evitando que o AdSense
        // ou o próprio site sirvam uma versão limitada por detectar WebView.
        String ua = settings.getUserAgentString();
        if (!ua.contains("StreamFlixApp")) {
            settings.setUserAgentString(ua + " StreamFlixApp/1.0");
        }

        webView.setWebViewClient(new InternalWebViewClient());
        webView.setWebChromeClient(new InternalWebChromeClient());
        webView.setDownloadListener(new InternalDownloadListener());
    }

    /** Trata navegação dentro do app x links que devem sair (anúncios, redes sociais, etc). */
    private class InternalWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost() == null ? "" : uri.getHost();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();

            // Mantém navegação dentro do app para o próprio domínio do site
            if (host.contains(BuildConfig.SITE_HOST)) {
                return false;
            }

            // Esquemas que não são http/https (whatsapp:, intent:, tel:, mailto:, market:, etc.)
            if (!scheme.equals("http") && !scheme.equals("https")) {
                openExternally(uri);
                return true;
            }

            // Domínios de anúncio/rastreamento conhecidos: sempre abrir por fora,
            // nunca dentro da WebView principal do app.
            if (isAdOrTrackingHost(host)) {
                openExternally(uri);
                return true;
            }

            // Qualquer outro domínio externo (ex: login social, redirecionamento de
            // pagamento) também abre por fora para não "perder" a navegação do app.
            openExternally(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);
            swipeRefresh.setRefreshing(false);
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
            // Mantém a validação padrão (segura); não ignora erros de SSL.
            super.onReceivedSslError(view, handler, error);
        }
    }

    private boolean isAdOrTrackingHost(String host) {
        String h = host.toLowerCase();
        String[] adHosts = {
                "doubleclick.net", "googlesyndication.com", "googleadservices.com",
                "google-analytics.com", "googletagmanager.com", "googletagservices.com",
                "adservice.google.com", "adnxs.com", "facebook.com/tr", "outbrain.com",
                "taboola.com", "criteo.com", "pubmatic.com", "rubiconproject.com"
        };
        for (String adHost : adHosts) {
            if (h.contains(adHost)) return true;
        }
        return false;
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Não foi possível abrir o link", Toast.LENGTH_SHORT).show();
        }
    }

    /** Trata uploads de arquivo, progresso, e principalmente popups de anúncio (window.open). */
    private class InternalWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            progressBar.setProgress(newProgress);
            if (newProgress >= 100) {
                progressBar.setVisibility(View.GONE);
            }
        }

        // Upload de arquivos (ex: foto de perfil, comprovante de pagamento)
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

        // CHAVE para anúncios que usam window.open()/target=_blank: ao invés de a
        // WebView travar ou o app "sumir", abrimos o popup numa Activity separada
        // que sabe se fechar sozinha. O app principal nunca é afetado.
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

            // Quando a URL não vem no HitTestResult (alguns SDKs de anúncio fazem
            // isso), criamos uma WebView "transporte" temporária só para capturar
            // a URL de destino e então delegamos à Activity de popup / navegador.
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

    /**
     * Botão voltar do Android: navega no histórico da própria WebView.
     * Só minimiza o app (não fecha o processo de forma abrupta) quando não há
     * mais histórico para voltar — isso evita o problema de "voltar do anúncio
     * fecha o app" que acontecia no wrapper PWA.
     */
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
    }

    // Pausa/retoma o WebView junto com o ciclo de vida da Activity (evita que
    // vídeos/áudio continuem tocando em segundo plano e economiza bateria),
    // SEM jamais destruir ou recarregar a página.
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

    /**
     * Esconde a barra de status (relógio/bateria) e a barra de navegação,
     * deixando o app em tela cheia imersiva. Reaplicado sempre que a janela
     * ganha foco de novo (ex: voltar de outro app), porque o Android tende a
     * restaurar as barras automaticamente.
     */
    private void setupImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setupImmersiveMode();
        }
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
