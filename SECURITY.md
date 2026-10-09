# Segurança e privacidade

## Dados fora do repositório

A cópia preparada contém código-fonte, configuração de build genérica, a logo e capturas de demonstração. Não inclui SDK local, caches, executáveis/APKs, chave de assinatura, credenciais de GitHub, exportações reais de conexão, screenshots de uso real ou o armazenamento de aparelhos lembrados. A revisão textual dos arquivos de publicação procura caminhos pessoais, nomes de máquina, IPs usados no teste físico, chaves privadas e padrões de tokens. Isso é uma revisão direcionada dos arquivos e da arquitetura, não uma auditoria independente completa de segurança.

As strings de token nos testes são fixtures fictícias. A senha fixa do formato PKCS12 no código Android não é uma credencial de conexão nem a chave de assinatura do APK: esse conteúdo fica dentro do armazenamento privado criptografado pelo Android Keystore. Nunca publique os dados reais gerados pelo app, mesmo que estejam criptografados.

## Pareamento e armazenamento

A comunicação de arquivos usa HTTPS. O primeiro pareamento precisa de código temporário e autorização no Android; a verificação exibida nas duas telas confirma a raiz TLS obtida no bootstrap. A porta HTTP de bootstrap fornece somente o certificado público, não arquivos ou tokens.

Ao lembrar um PC, o Android cria uma credencial específica para ele. O Android protege a identidade e a lista de autorizações com o Keystore; o Windows protege o armazenamento com DPAPI do usuário atual. A descoberta UDP transmite apenas informações públicas e um nonce. A reconexão confere a raiz salva do celular antes de enviar o token.

Esquecer o PC no Android revoga sua credencial e encerra conexões. Remover o celular apenas no PC não revoga a autorização no Android. Exportações JSON/QR contêm acesso de sessão e devem permanecer privadas. Quem for autorizado pelo celular poderá modificar os arquivos compartilhados; a confirmação inicial é relevante mesmo dentro do Wi-Fi.

## Atualizações

O atualizador consulta somente o repositório oficial configurado. Confere tamanho e SHA-256 dos assets anunciados, repete a verificação antes de instalar e exige decisão do usuário. No Android também exige o mesmo pacote, a mesma assinatura instalada e `versionCode` maior. O checksum Windows se apoia no GitHub; não substitui assinatura independente de distribuição. Nenhuma senha/chave de produção é embutida no código.

A distribuição Android de teste usa debug. A primeira assinatura de produção deve ser definida antes de publicar o APK definitivo; trocar essa assinatura quebra a atualização direta do preview. Veja [updates.md](desktop/docs/updates.md).

O fluxo foi concebido para rede local. A operação de atualização usa internet/GitHub, sem enviar nomes de arquivos, tokens de pareamento ou conteúdo transferido. Diagnósticos e screenshots compartilhados pelo usuário devem remover informações pessoais antes de serem publicados.
