# Contrato de integração PC ↔ Android, v1

Este contrato é implementado pelo cliente PC e pelo servidor do projeto `wifi-files-android`. Todos os endpoints exigem HTTPS e `Authorization: Bearer <token>`. Um certificado local deve incluir o IP/nome usado na URL nos Subject Alternative Names e ser fornecido ao PC como PEM para confiança explícita. O PC não desativa a validação de certificados.

O Android exporta JSON e QR com `{version:1,address,token,certificatePem}`. O PC importa o JSON ou decodifica uma imagem do QR; ambos preservam a validação TLS. A identidade raiz do Android é persistente e protegida pelo Android Keystore. O certificado TLS de sessão muda quando necessário, assinado por essa raiz. O token de sessão muda ao reiniciar o compartilhamento; o exportado por JSON/QR é temporário. Cópias desse documento contêm uma credencial e não devem ser publicadas. O acesso lembrado usa um token próprio por PC: no Windows, ele é armazenado com DPAPI, e no Android com criptografia apoiada pelo Keystore.

## Endpoints

### Pareamento por IP e código

O endpoint HTTP `GET :8444/v1/certificate` entrega somente `{certificatePem}` público. O cliente obtém esse certificado, gera um nonce aleatório de 32 bytes em hexadecimal e conecta por HTTPS validando o certificado e o endereço. `POST :8443/v1/pair` recebe `{code,nonce,clientId,clientName}`. O servidor exige código de seis dígitos, validade de cinco minutos, limite global de cinco tentativas e somente uma solicitação pendente. Ambos exibem a verificação: primeiros oito bytes de SHA-256 de `certificatePem + nonce`, exibidos em 16 caracteres hexadecimais maiúsculos, agrupados de quatro em quatro. O usuário compara as telas antes de aprovar no Android. Essa comparação confirma a identidade do certificado obtido inicialmente.

Após aprovação em até 60 segundos, o servidor entrega `{token,remembered}` por HTTPS e consome o código. Recusa/timeout retornam 403; código errado retorna 401; limite/expiração retornam 429. Arquivos nunca são acessíveis pelo endpoint HTTP. A porta de bootstrap é a porta HTTPS + 1; em produção são 8443/8444. Se “Lembrar dispositivo” for marcado no Android, um token persistente específico desse PC é entregue e pode ser revogado em Dispositivos. Nas próximas conexões, o PC confia somente na raiz salva desse celular e usa o token salvo. Caso contrário, o token é válido apenas na sessão.

| Método | Caminho | Uso |
|---|---|---|
| GET | `/v1/device` | Informações do celular |
| GET | `/v1/files?path=...` | Lista de uma pasta |
| GET | `/v1/content?path=...` | Download em streaming |
| PUT | `/v1/content?path=...&overwrite=false` | Upload binário em streaming |
| POST | `/v1/actions` | Criar pasta, renomear ou excluir |

### GET /v1/device

```json
{"name":"Meu Android","root":"/","freeBytes":40802189312,"totalBytes":137438953472}
```

Os caminhos são identificadores virtuais relativos ao armazenamento autorizado, com `/` como separador. O servidor mapeia esses identificadores para caminhos/URIs Android. Não aceitar `..`, escapes de raiz, links que escapem da área permitida nem nomes reservados. `root` é a pasta inicial acessível.

### GET /v1/files

```json
{
  "path":"/Music",
  "parent":"/",
  "entries":[
    {"name":"Álbuns","path":"/Music/Álbuns","isDir":true,"size":0,"modified":null},
    {"name":"Faixa.mp3","path":"/Music/Faixa.mp3","isDir":false,"size":8808038,"modified":1791504000}
  ]
}
```

Na raiz, `parent` é null. `modified` é Unix time em segundos ou null. Não listar conteúdo fora das permissões efetivamente concedidas pelo usuário.

### GET /v1/content

Resposta `200` com corpo binário e `Content-Length`, quando conhecido. O PC escreve em arquivo temporário e usa publicação sem sobrescrita após receber o corpo completo. Nomes repetidos no PC geram erro; o original é preservado.

### PUT /v1/content

`Content-Type: application/octet-stream`; corpo binário com `Content-Length`. O servidor deve:

1. Validar autenticação, caminho, nome, espaço e ausência de destino existente.
2. Escrever em um arquivo temporário/entrada pendente.
3. Descartar o temporário se houver desconexão, cancelamento ou tamanho incorreto.
4. Publicar sem sobrescrever o destino somente após concluir.
5. Atualizar o índice de mídia, se aplicável, e retornar `201` ou `204`.

O progresso de upload do PC indica bytes lidos/enviados; a conclusão só é marcada depois da resposta de sucesso do servidor. Ainda não há comparação de hash entre os dispositivos.

### POST /v1/actions

```json
{"action":"mkdir","path":"/Music","name":"Álbuns"}
```

```json
{"action":"rename","path":"/Music/antigo.mp3","name":"novo.mp3"}
```

```json
{"action":"delete","path":"/Music/antigo.mp3","name":null}
```

Retornar `204` em sucesso. `mkdir` cria um filho de `path`; `rename` mantém a pasta pai e não substitui outro item. `delete` poderá ser permanente no Android; o PC mostra confirmação antes de chamar esse endpoint.

## Erros

`401/403`: não autorizado; `404`: item inexistente; `409`: conflito de nome; `507`: espaço insuficiente; outros códigos de erro: falha de operação. Não enviar informações privadas em respostas de erro.

## Ciclo de vida

O servidor só fica disponível durante uma sessão iniciada pelo usuário no Android. Transferências em segundo plano exigirão o mecanismo Android adequado. A versão PC faz uma transferência por vez e cancela o cliente ao desconectar. Uma transferência interrompida deve ser reiniciada; retomada não faz parte do v1.

## Descoberta de dispositivos lembrados

UDP 8445 recebe `wifi-files-discover-v1` com nonce hexadecimal aleatório e responde `wifi-files-device-v1` com o mesmo nonce, ID público do dispositivo, nome e porta TLS. Não transmite token nem chave privada. O PC procura na rede local e no último endereço conhecido; autentica o aparelho pelo certificado raiz salvo antes de enviar a credencial. Broadcast bloqueado ou Wi-Fi com isolamento de clientes pode impedir a descoberta. Esquecer no Android revoga a autorização e encerra as conexões atuais.
