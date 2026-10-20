# ConsigliereIA

Widget Android que junta agenda e tarefas numa lista curta, com resumo do dia e conversa por texto ou voz:

- **Agenda do celular**: todas as agendas sincronizadas (Google, vivo…), lidas localmente, sem login.
- **Todoist**: tarefas atrasadas e dos próximos dias (token pessoal da API).

Mostra atrasadas primeiro (em vermelho), depois tudo em ordem de data. Tocar num item abre o evento ou a tarefa no app de origem.
O widget atualiza a cada 30 min, quando a agenda muda e pelo botão ↻.

Para mudar de dia: no widget, o **»** joga o item para o dia seguinte (atrasada vem para hoje); no app, na aba Agenda, segure o item e solte num dia da barra. Recorrentes e convites de outras pessoas ficam de fora.

## Inteligência: dois modos

- **Automático (sem IA):** regras no próprio celular. Entendem "amanhã às 9h", "sexta 14:30", "dia 21/10",
  "22 de outubro", "8 da noite", "meio-dia"… Grátis e nada sai do aparelho.
- **Minha IA:** você escolhe **Claude**, **ChatGPT** ou **Gemini** e cola a sua chave de API. O app chama a API
  direto do celular (sem servidor no meio); o custo vai para a sua conta.
  - Claude: console.anthropic.com → API Keys (modelo padrão `claude-opus-5-5`)
  - ChatGPT: platform.openai.com → API keys (padrão `gpt-5-mini`)
  - Gemini: aistudio.google.com → Get API key (padrão `gemini-3.8-flash`)
  - O campo "Modelo" permite trocar por outro (ex.: `claude-haiku-5-5`, mais barato).

Onde a inteligência é usada:

1. **Resumo do dia** no topo do widget ("Hoje: 2 compromissos · 1 atrasada. Próximo: Exame 09:00").
   Com IA, só é recalculado quando a lista muda.
2. **Compartilhar → "ConsigliereIA: criar compromisso"** em qualquer app: abre uma tela com título, data e hora
   preenchidos para criar o evento na agenda ou a tarefa no Todoist.
3. **Ler mensagens** (opcional): lê as notificações de WhatsApp, Telegram, Gmail, Outlook e SMS. Quando uma mensagem
   parece um compromisso, aparece uma sugestão para confirmar. Nada é criado sozinho. Com IA ligada, as regras
   filtram antes e só as mensagens candidatas vão para a IA.

## Levar para outro aparelho

Configurações → "Levar para outro aparelho" → **Exportar (QR)** num aparelho e **Importar** no outro.
O QR contém o token do Todoist e a chave de IA.

## Instalar

1. Cada push na `main` gera o APK pelo GitHub Actions.
2. No celular, abra a release **latest** do repositório e baixe `agenda-widget.apk`.
3. Instale (permita "instalar apps desconhecidos" para o navegador ou o app do GitHub).
4. Abra o **Agenda Widget**, configure as fontes e adicione o widget **Agenda e tarefas** na tela inicial.
5. Em celulares vivo: Configurações → Bateria → libere o app em "sem restrições", e permita a inicialização automática.

## Todoist

Todoist → Configurações → Integrações → Desenvolvedor → copie o **Token da API** e cole no app.

## Play Store

O build de loja (`bundleRelease`, arquivo `.aab`) é assinado com a chave de upload guardada nos
segredos do GitHub (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`).
Sem os segredos, o CI gera só o APK de uso pessoal. A chave em `app/agenda.keystore` é pública
(repositório aberto) e serve só para o APK de teste; nunca a use na loja.

Política de privacidade: `docs/privacidade.html` (GitHub Pages).

## Estrutura

- `data/CalendarSource.kt`: eventos via `CalendarContract.Instances`
- `data/TodoistSource.kt`: `GET /api/v1/tasks/filter?query=overdue | next N days`
- `data/AgendaRepository.kt`: junta, ordena e guarda o cache
- `widget/AgendaWidget.kt`: widget em Jetpack Glance
- `widget/RefreshWorker.kt`: atualização periódica e ao mudar a agenda (WorkManager)
- `MainActivity.kt`: tela de configuração e prévia
