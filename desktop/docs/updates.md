# Atualizações do SFLink

O PC e o Android consultam `https://api.github.com/repos/NskBR/SFLink-APP/releases/latest` ao abrir. O PC avisa quando há versão nova e mostra o controle em **Configurações**. No Android, o controle está em **Dispositivos**. A consulta exige internet; arquivos e credenciais de pareamento não são enviados ao GitHub.

## Contrato de uma release

- Tag estável `vMAJOR.MINOR.PATCH`, por exemplo `v0.3.1`; drafts e prereleases não são instalados por este canal.
- Windows x64: `SFLink_0.3.1_x64-setup.exe`.
- Android: `SFLink_0.3.1_android.apk`.
- Os assets precisam ter tamanho positivo, nome correspondente à versão e `digest` SHA-256 na API do GitHub. Sem checksum válido, o app recusa o download instalável.
- Atualize `package.json`, `package-lock.json`, Cargo, Tauri e a versão exibida no desktop. No Android, incremente **também** `versionCode` em `app/build.gradle.kts`.
- Publicar uma tag sem anexar esses arquivos não disponibiliza uma atualização instalável. O repositório vazio/sem release retorna ausência de atualização.

O download usa HTTPS e arquivo temporário. O app confere tamanho e SHA-256, remove o temporário em falhas/cancelamento e repete a conferência antes de instalar. O PC aceita somente instaladores desse repositório; o Android também confere o pacote, a versão e a assinatura instalada. O SHA-256 detecta alterações em relação ao asset anunciado; a confiança do atualizador Windows depende da conta/repositório GitHub, sem uma chave de assinatura independente do GitHub neste fluxo.

## Windows

O usuário escolhe baixar e depois **Instalar e fechar SFLink**. A fila deve terminar antes de instalar. O instalador NSIS convencional abre e o aplicativo fecha; a instalação e eventuais permissões do Windows continuam na tela do instalador. Não há instalação silenciosa. Se cancelar o instalador, abra o aplicativo novamente. O modo de desenvolvimento recusa instalar.

Build: `npm ci` e `npm run desktop:build`. O instalador fica em `src-tauri/target/release/bundle/nsis/`.

## Android

O usuário escolhe baixar e depois **Instalar atualização**. Caso necessário, o Android mostra a tela para permitir que o SFLink instale aplicativos. Depois de autorizar, volte e toque em Instalar novamente. A confirmação final pertence ao instalador do Android. A conexão de arquivos é encerrada ao abrir o instalador, após verificar que não há transferência ativa.

Uma atualização deve manter `applicationId = com.wififiles.android`, a mesma chave de assinatura e um `versionCode` maior. Isso preserva os dados privados do app, incluindo os PCs lembrados e sua identidade de conexão. A troca do nome visível/logo não muda o identificador.

**Os APKs preview atuais usam a chave de desenvolvimento local.** Não publique essa chave e não trate preview como distribuição de produção. Um APK assinado com uma chave de produção diferente não atualiza o preview instalado. A primeira migração para produção precisa ser planejada; desinstalar o preview apaga suas autorizações salvas, exigindo novo pareamento.

O build release recebe os segredos somente por ambiente:

```
SFLINK_KEYSTORE_PATH
SFLINK_KEYSTORE_PASSWORD
SFLINK_KEY_ALIAS
SFLINK_KEY_PASSWORD
```

Guarde a chave de produção fora do repositório, com backup privado, e use a mesma nas releases seguintes. Sem essas variáveis o build release fica sem assinatura de distribuição; não o publique. `gradlew.bat :app:assembleRelease` gera o APK de produção quando a assinatura está configurada. Para testes locais, `:app:assemblePreview` continua usando debug; nunca inclua o keystore, senhas, `local.properties` ou exportações de conexão no Git.

Não publique versões novas sem testar a atualização de uma versão anterior assinada com a mesma chave, verificando que os dispositivos lembrados continuam disponíveis. A 0.3.0 inaugura o canal oficial de distribuição. Os APKs de produção passam a usar uma chave própria permanente, distinta do preview. O teste completo entre duas releases públicas exige uma versão posterior; os testes locais de regras/build não substituem essa validação.

O script `tools/build-release.ps1` na raiz do repositório automatiza os builds locais e os checksums, sem publicar e sem GitHub Actions. A senha/chave continuam sendo fornecidas por ambiente.

Certificado público da assinatura Android (SHA-256): `0ce84853a7b97ea03b738c2b95c90e3c05e4fc6aede95fc891a6067abe80e58e`.

Referências: [GitHub Releases API](https://docs.github.com/en/rest/releases/releases), [assinatura Android](https://developer.android.com/studio/publish/app-signing), [permissão de instalação](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()).
