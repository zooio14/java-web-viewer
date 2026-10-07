# Java Web Viewer

Um protótipo de "navegador dentro do site" usando **Java + Spring Boot**.

## Importante

Isto não é um Chrome completo e não foi feito para contornar bloqueios de Wi‑Fi, filtros de escola/empresa,
CSP, X-Frame-Options ou controles de acesso.

Ele funciona como um **visualizador web controlado**:

- o usuário continua na mesma interface;
- o backend Java baixa a página;
- links permitidos continuam dentro do visualizador;
- JavaScript remoto, formulários, iframes e embeds são removidos;
- há uma allowlist de domínios;
- IPs locais/privados são bloqueados para reduzir risco de SSRF.

Sites modernos e jogos complexos podem não funcionar nesse modo, especialmente os que dependem fortemente de
JavaScript, autenticação, DRM, WebSockets ou políticas próprias de segurança.

## Requisitos

- Java 17+
- Maven 3.6.3+

O projeto usa Spring Boot 4.1.1 e jsoup 1.23.2.

## Rodar

```bash
mvn spring-boot:run
```

Depois abra:

```text
http://localhost:8080
```

## Adicionar sites permitidos

Edite:

```text
src/main/resources/application.properties
```

Exemplo:

```properties
viewer.allowed-domains=wikipedia.org,example.com,developer.mozilla.org,seusite.com
```

Ou defina uma variável de ambiente:

```bash
VIEWER_ALLOWED_DOMAINS=wikipedia.org,example.com,seusite.com
```

Não há suporte a `*` de propósito: um proxy aberto é perigoso.

## GitHub Pages

GitHub Pages não roda Java. Você pode manter o código no GitHub, mas precisa hospedar o backend Java em um
serviço que execute aplicações Java (ou em seu próprio servidor).

## O que seria necessário para um "navegador completo"

Um navegador realmente completo dentro de um site normalmente exigiria uma instância de navegador remota
(Chromium/Firefox) rodando no servidor e transmitindo a tela/interações. Isso é bem mais pesado e deve ser
feito com autenticação, isolamento e políticas de acesso. Este protótipo deliberadamente não implementa um
proxy/navegador remoto irrestrito.
