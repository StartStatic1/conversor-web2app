# StreamFlix - App Android (WebView nativo)

Projeto Android Studio pronto, gerando um APK que carrega `https://streamflix-red.zeabur.app`
como um app nativo de verdade (não é PWA/TWA empacotado por ferramenta automática).

## Por que isso resolve o problema que você teve com o gerador da Microsoft

| Problema que você teve | Como foi resolvido aqui |
|---|---|
| Anúncio do AdSense fechava ou recarregava o app | Links de anúncio e popups (`window.open`) são interceptados e abertos numa Activity separada (`AdPopupActivity`) ou no navegador externo. O WebView principal nunca é tocado. |
| Botão voltar saía do app no meio de um anúncio | O botão voltar primeiro tenta `webView.goBack()` no histórico interno; só minimiza o app quando não há mais para onde voltar. |
| Rotação de tela recarregava a página | O `AndroidManifest.xml` declara `configChanges` para orientação/tamanho de tela, então a Activity **não é recriada** ao girar — a WebView só é redimensionada. |
| AdSense "bloqueado"/anúncios não carregavam | Cookies de terceiros habilitados, DOM Storage ligado, `JavaScript` habilitado, popups de JS habilitados — tudo que o AdSense exige para funcionar em WebView. |

## Como gerar o APK

1. Instale o **Android Studio** (gratuito): https://developer.android.com/studio
2. Abra o Android Studio → **Open** → selecione a pasta `StreamFlixApp` (a raiz, onde está o `settings.gradle`)
3. Aguarde o Gradle sincronizar (primeira vez baixa o wrapper automaticamente — precisa de internet)
4. No menu superior: **Build → Generate Signed Bundle / APK → APK**
   - Para testar rápido, pode usar **Build → Build APK(s)** (gera um APK de debug, instalável direto)
5. O APK fica em `app/build/outputs/apk/debug/app-debug.apk` (ou `release/` se assinado)
6. Copie o `.apk` pro celular e instale (ative "fontes desconhecidas" se pedir)

## Se quiser mudar algo

- **Trocar a URL do site**: edite `SITE_URL` e `SITE_HOST` em `app/build.gradle`
- **Trocar o nome do app**: edite `app_name` em `app/src/main/res/values/strings.xml`
- **Trocar o ícone**: substitua os PNGs em `app/src/main/res/mipmap-*/`
- **Trocar a cor do tema**: edite `bg_dark` e `accent_red` em `app/src/main/res/values/colors.xml`
- **Bloquear mais domínios de anúncio/rastreamento**: adicione na lista `adHosts` dentro de `MainActivity.java`

## Permissões já configuradas

- Internet / estado de rede
- Câmera (opcional, para upload de foto se o site pedir)
- Download de arquivos abre automaticamente pelo navegador padrão do sistema

## Observação sobre política do AdSense

O Google AdSense tecnicamente não recomenda anúncios dentro de WebView de apps nativos
(prefere SDK próprio, como AdMob). Isso não impede o app de funcionar tecnicamente — e
foi corrigido aqui — mas vale saber que para monetização 100% dentro das regras do
Google a longo prazo, a alternativa seria migrar os anúncios do site para o **AdMob**
nativo dentro do próprio app. Posso te ajudar com isso depois se quiser.
