# Validação local — SFLink 0.3.0

- Build Svelte/TypeScript sem erros ou avisos; compilação Windows/NSIS validada.
- Rust: 11 testes passaram; quatro testes com pareamento manual permanecem ignorados por padrão.
- Android: nove testes JVM passaram; build preview e lint concluídos sem erros. O lint contém avisos de SDK/dependências e permissões de gerenciamento de arquivos.
- Integração no emulador: lembrar um PC, recriar sessão, autenticar por token salvo, transferir arquivo, proteger conflitos e revogar autorização passaram.
- Navegação do desktop: Configurações abre/oculta corretamente sem desmontar o estado do atualizador ao trocar de página.
- Capturas: desktop renderizado com fixtures e componentes reais; Android renderizado pelo teste de demonstração no emulador. Logo verificada nas duas interfaces.

## Limites desta validação

A 0.3.0 é a primeira release desse canal. O ciclo completo de baixar uma versão posterior e substituir uma instalação anterior ainda exige duas releases sequenciais reais. A permanência das autorizações durante uma atualização de produção também precisa ser conferida nesse ciclo.

As transferências em celular físico foram confirmadas durante o desenvolvimento anterior. Os resultados desta alteração são de build/testes locais e emulador; não equivalem a nova validação de atualização ou descoberta em todas as redes/celulares físicos.

## Preparação da primeira release

APK release compilado localmente com R8, sem flag debuggable, assinado com a chave de produção SFLink; assinatura validada pelo apksigner. Lint release: zero erros. O APK assinado passou, em emulador temporário, no pareamento por código com conferência TLS, lembrança do PC, upload/download de 2 MB, exclusão do arquivo de teste e reconexão autenticada após reiniciar o app.

O instalador Windows foi compilado localmente a partir dos mesmos arquivos de execução/configuração publicados; está sem assinatura Authenticode. Nenhum workflow de Actions foi criado ou usado. A primeira release estabelece o canal; a substituição entre duas releases públicas permanece pendente para uma versão seguinte.

## Desktop 0.4.0

Build frontend sem erros/avisos. Treze testes Rust passaram (quatro testes de conexão manual ignorados). Cobertura adicional: miniatura PNG reduzida, rejeição de dados corrompidos e escolha de nome sem substituir arquivos existentes. Validação de interface com IPC simulado: Ctrl+F no painel ativo, Ctrl+A nos resultados filtrados, Ctrl+Delete com confirmação, F2, duplo clique aguardando o download para abrir, status Enviando/Baixando e carregamento de miniatura. A simulação não substitui a validação dessas novidades com um celular físico. O WebView2 tem os atalhos de navegador desativados nativamente para evitar a busca de página no Ctrl+F.

## Desktop 0.5.0

- Frontend sem erros/avisos; 14 testes Rust passaram, quatro testes manuais de rede ignorados. Novo teste grava uma capa PNG incorporada em tags de áudio e valida sua leitura/redução; dados inválidos são rejeitados.
- Interface validada com IPC simulado e áudio WAV real servido no ambiente de teste: reprodução/pausa, duração, volume, atalhos, histórico por botões de mouse, menu de renomear, temas e restauração do tema após reload.
- Auxiliar de atualização executado no Windows com instalador e aplicativo de teste isolados: SHA incorreto rejeitado, handshake ready/go, parâmetros silenciosos, espera por término e reabertura. Esse teste não substitui uma atualização completa de duas instalações reais do SFLink/NSIS e não alterou a instalação do usuário.
- O teste de interface usa protocolo de assets simulado; a disponibilidade de codecs no Windows/WebView2 deve ser conferida no uso real. Os novos fluxos não foram validados de novo com celular físico.

## Desktop 0.6.0

Validação de interface com relógio controlado: zero consultas antes de 10 segundos, primeira aos 10, segunda aos 70, e nenhuma chamada de download/instalação após essas consultas mesmo com versão disponível. O clique explícito na titlebar chama download e depois instalação, nessa ordem. O teste usa IPC simulado e não executa instalador real.

Áudio de teste real: reprodução/pausa, seek, volume, anterior/próxima, aleatório e repetição passaram na interface. Fotos carregam com superfície transparente, sem caixa de fundo. Persistência de temas, navegação por mouse e menus continuaram passando. Essas verificações não substituem o uso com os arquivos/codecs do usuário e celular físico.
