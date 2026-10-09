# Java Web Viewer

Protótipo de navegador remoto com **Java/Spring Boot + Chromium/Playwright**. O Chromium roda no servidor; o frontend recebe imagens JPEG da tela por WebSocket e envia navegação, mouse, rolagem e teclado de volta. O conteúdo remoto não é executado dentro do HTML do visualizador.

O acesso exige um token. Por padrão, o modo `public` permite abrir sites públicos sem cadastrar cada domínio. O modo `allowlist` restringe a navegação aos domínios configurados. Os dois modos bloqueiam destinos locais e privados. O projeto foi feito para navegação autorizada e não oferece proxy aberto nem mecanismos para contornar filtros de rede ou controles de acesso dos sites.

## Como funciona

```text
Frontend do visualizador
    ↕ WebSocket autenticado: imagens + comandos
Spring Boot → Chromium isolado por sessão
    ↕ gateway interno autenticado e validação de destino
Sites públicos permitidos pelo modo configurado
```

Cada sessão possui seu próprio navegador/contexto, cookies e armazenamento temporários. Encerrar a sessão, expirar o tempo limite ou reiniciar o serviço apaga esse estado. Não há persistência de logins nem compartilhamento de cookies entre usuários. O token do deploy é enviado apenas ao endpoint de login; depois, uma credencial curta em cookie `HttpOnly` e `SameSite=Strict` autentica a conexão. Em HTTPS o cookie também é `Secure`. O frontend não salva o token no armazenamento do navegador ou na URL.

O gateway é um detalhe interno do processo: escuta apenas em loopback, exige uma credencial aleatória e não possui endpoint público. Ele valida cada conexão HTTP/HTTPS e conecta a um endereço IP público previamente verificado, evitando nova resolução DNS entre validação e conexão. A política também se aplica a recursos auxiliares e redirecionamentos. Em `allowlist`, imagens, scripts e APIs hospedados em outros domínios exigem autorização na lista. Em `public`, esses recursos podem usar qualquer domínio público válido, mantendo a verificação de DNS e IP.

## Versões e requisitos

- Spring Boot **4.1.1**, alvo Java **17**; build e testes de CI em Java 17 e 25.
- Playwright Java **1.63.0**; a imagem Docker usa **exatamente a mesma versão** para encontrar o Chromium correspondente.
- Guava **33.7.2-jre**, para validação de endereços e sufixos públicos.
- Para desenvolvimento local: Java 17+ e Maven 3.6.3+; instalar Chromium com o comando abaixo.
- Docker: build com Maven **3.10.0/Temurin 21** e execução com a imagem oficial Playwright Java **v1.63.0-noble**, que contém JDK 25 e as dependências do navegador.

## Rodar localmente

Na raiz do projeto:

```bash
mvn --batch-mode --no-transfer-progress verify
mvn --batch-mode --no-transfer-progress exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
export VIEWER_ACCESS_TOKEN="$(openssl rand -hex 32)"
mvn spring-boot:run
```

No Linux, use `install --with-deps chromium` caso as bibliotecas de sistema ainda não estejam instaladas. Abra `http://localhost:8080`, informe o valor de `VIEWER_ACCESS_TOKEN`, conecte e navegue para `https://example.com`. Clique na tela para enviar o teclado ao Chromium. A interface oferece voltar, avançar, recarregar e encerrar.

Guarde o token gerado em um gerenciador de senhas antes de sair do terminal. Use um token diferente em cada ambiente. Em um deploy público, use o domínio HTTPS do serviço e compartilhe o token apenas com pessoas autorizadas. Para revogá-lo, troque a variável e reinicie o serviço.

## Configuração

Todas as opções estão em `src/main/resources/application.properties` e podem ser sobrescritas por variáveis de ambiente:

| Variável | Padrão | Uso |
| --- | --- | --- |
| `PORT` | `8080` | Porta HTTP; aceita a porta fornecida pelo Railway. |
| `VIEWER_ACCESS_TOKEN` | vazio | Segredo com pelo menos 32 caracteres. Sem ele, a criação de sessões retorna `503`. |
| `VIEWER_DESTINATION_MODE` | `public` | `public` permite domínios públicos válidos sem cadastro prévio; `allowlist` restringe os destinos à lista. |
| `VIEWER_ALLOWED_DOMAINS` | `wikipedia.org,example.com,developer.mozilla.org` | Lista usada em `allowlist`, separada por vírgulas; cada domínio inclui seus subdomínios. |
| `VIEWER_MAX_SESSIONS` | `2` | Navegadores simultâneos, de 1 a 8. |
| `VIEWER_IDLE_TIMEOUT_SECONDS` | `300` | Encerramento por inatividade, de 30 a 3600 segundos. |
| `VIEWER_SESSION_MAX_SECONDS` | `1800` | Duração máxima da sessão, de 60 a 7200 segundos. |
| `VIEWER_FPS` | `8` | Limite de imagens por segundo, de 1 a 15. |
| `VIEWER_JPEG_QUALITY` | `70` | Qualidade JPEG, de 40 a 90. |
| `VIEWER_CHROMIUM_SANDBOX` | `true` | Sandbox do Chromium; depende do suporte a namespaces do host Linux. |

Para manter os destinos restritos:

```bash
export VIEWER_DESTINATION_MODE=allowlist
export VIEWER_ALLOWED_DOMAINS=example.com,developer.mozilla.org
```

O padrão já permite acessar sites públicos sem enumerar os domínios. Para deixar essa escolha explícita:

```bash
export VIEWER_DESTINATION_MODE=public
export VIEWER_CHROMIUM_SANDBOX=true
```

O token continua obrigatório nesse modo e a interface informa o modo ativo. `public` amplia os destinos permitidos às pessoas autenticadas; não publica uma URL de proxy para terceiros. Use esse modo somente onde você tem autorização para acessar os sites. Permitir o destino não garante compatibilidade com todos os sites: CAPTCHA, DRM e outras limitações estão descritos abaixo.

A lista de domínios não aceita `*` nem sufixos públicos como `com.br`. Em **ambos os modos**, URLs com endereços IP, `localhost`, credenciais ou protocolos além de HTTP/HTTPS são rejeitadas. Somente as portas 80/443 são permitidas. DNS que resolve para endereços privados, loopback, link-local, multicast, reservados ou endpoints de metadados é rejeitado, inclusive se a resposta misturar IPs públicos e privados. Mantenha `VIEWER_CHROMIUM_SANDBOX=true` ao navegar em sites públicos.

## Docker e Railway

O `Dockerfile` faz o build, incorpora o Chromium correspondente, executa com o usuário sem privilégios `pwuser` e usa `tini` para recolher processos filhos. `railway.json` seleciona esse Dockerfile, configura `/health` e mantém a política de reinício por falha.

1. Conecte o serviço Railway ao repositório e mantenha o diretório raiz do projeto.
2. Nas variáveis do serviço, configure `VIEWER_ACCESS_TOKEN` com um segredo novo de pelo menos 32 caracteres. Gere-o localmente com `openssl rand -hex 32`.
3. O modo padrão `public` permite abrir sites públicos sem cadastrar domínios. Você pode explicitá-lo com `VIEWER_DESTINATION_MODE=public`; mantenha `VIEWER_CHROMIUM_SANDBOX=true`. Para preservar o modo restrito anterior, defina `VIEWER_DESTINATION_MODE=allowlist` e configure `VIEWER_ALLOWED_DOMAINS`. Não coloque tokens no Dockerfile, no código ou no Git.
4. Remova um eventual comando antigo de inicialização que substitua o `ENTRYPOINT`; deixe o Railway construir o Dockerfile. Preserve o domínio público existente e a variável `PORT` fornecida pelo serviço.
5. Após o deploy, verifique `/health`, abra o frontend por HTTPS, autentique e confirme que uma sessão realmente inicia e recebe imagens.

O container escuta em `PORT` (8080 por padrão); não precisa de volume. Use uma única réplica neste protótipo: credenciais e sessões ficam na memória do processo. Réplicas adicionais exigem roteamento de sessões e estado compartilhado. CPU/RAM necessárias dependem dos sites e do número de sessões; comece com uma sessão em ambientes limitados e acompanhe uso e logs.

### Sandbox e limites do ambiente

O padrão é `VIEWER_CHROMIUM_SANDBOX=true`, sem fallback automático. Chromium em Linux precisa criar namespaces; alguns hosts de containers restringem essa operação. Assim, um container que inicia pode ainda falhar ao abrir a primeira sessão. O health check verifica o gateway interno e a presença do executável, **não lança um Chromium** e não comprova que o sandbox funciona no host.

Se os logs indicarem falha de sandbox, prefira um host que permita os namespaces e um perfil seccomp apropriado. A [documentação oficial de Docker do Playwright](https://playwright.dev/java/docs/docker) descreve usuário separado e perfil seccomp. A imagem é destinada a testes/desenvolvimento; este é um protótipo, não um serviço multiusuário endurecido para navegar em conteúdo arbitrário.

`VIEWER_CHROMIUM_SANDBOX=false` é uma opção explícita para testes com conteúdo controlado em hosts restritos. Ela reduz o isolamento do navegador e não deve ser tratada como solução equivalente a um sandbox ativo. Para produção, use também isolamento do serviço e política de saída de rede no host; a validação da aplicação não substitui essas camadas.

## Verificação e CI

```bash
# Build, testes da política de destinos e testes de autenticação/protocolo
mvn --batch-mode --no-transfer-progress verify

# Depois de instalar Chromium: teste com navegador real e conteúdo controlado
mvn --batch-mode --no-transfer-progress -Dviewer.browser-tests=true verify

# Com Docker disponível
docker build -t java-web-viewer .
docker run --rm -p 8080:8080 \
  -e VIEWER_ACCESS_TOKEN -e VIEWER_DESTINATION_MODE \
  -e VIEWER_ALLOWED_DOMAINS java-web-viewer
```

O workflow `.github/workflows/build.yml` roda em push, pull request e execução manual: faz build/testes em Java 17 e 25, instala Chromium para o teste real e constrói a imagem Docker. Também executa os testes com o Chromium/driver presentes na imagem construída, usando `pwuser` e fontes montadas somente para o teste. O teste de inicialização da imagem verifica `/health`, frontend, `PORT` customizado e usuário sem privilégios. Os testes de navegador usam conteúdo controlado com sandbox desativado no runner; eles não atestam o sandbox de um deploy Railway nem disponibilidade de todos os sites externos.

`GET /health` retorna `200 {"status":"UP"}` quando o gateway e o executável estão disponíveis, ou `503 {"status":"DOWN"}`. Não exige token, para continuar compatível com o health check do deploy. `GET /api/config` informa dimensões, modo ativo e domínios configurados; a lista define os destinos somente em `allowlist`. O antigo `GET /api/view?url=...` retorna **410 Gone**: use a sessão autenticada na página inicial.

## Limitações do protótipo

- A transmissão é de imagens, com viewport de 1280×720. Não há áudio, WebRTC de vídeo, download de arquivos ou sincronização de clipboard.
- WebSockets dos sites, service workers, popups e downloads são bloqueados para limitar canais de saída e funções fora do protótipo. O WebSocket entre frontend e servidor continua habilitado.
- CAPTCHA, autenticação de terceiros, jogos, streaming e DRM podem falhar em qualquer modo. Em `allowlist`, dependências em domínios fora da lista também falham; autorize apenas os domínios necessários e confiáveis. O modo `public` remove essa necessidade de cadastro, mantendo as demais limitações.
- Em `allowlist`, cada domínio autoriza todos os seus subdomínios; escolha domínios sob controle confiável. Em `public`, as pessoas autenticadas podem escolher qualquer domínio público válido. O servidor mantém as conexões externas e continua sujeito às regras da sua rede e às políticas dos sites.
- Não há contas individuais, recuperação de sessão ou perfis persistentes. Quem possui o token tem autorização para criar sessões dentro dos limites configurados.
- GitHub Pages não executa Java/Chromium. O backend precisa de um serviço com execução de containers ou de um servidor próprio.

## Referências de compatibilidade

- [Requisitos do Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
- [Docker e correspondência de versões do Playwright Java](https://playwright.dev/java/docs/docker)
- [Manifesto das imagens oficiais do Maven](https://github.com/docker-library/official-images/blob/master/library/maven)
- [Health checks e PORT no Railway](https://docs.railway.com/deployments/healthchecks)
