# Consigliere

Seu conselheiro pessoal no Android: agenda e tarefas juntas num widget, um resumo do que importa no dia
e um assistente que anota por você, por texto ou voz.

## O que ele faz

- **Agenda + Todoist numa lista só:** eventos de todas as agendas do celular (Google e outras, lidas localmente,
  sem login) e tarefas do Todoist, com atrasadas em destaque.
- **Conselho do dia:** um resumo curto do que priorizar, com aviso de tempo quando afeta um compromisso
  ("leve guarda-chuva").
- **Conversa por texto ou voz:** "dentista quinta às 15h" vira evento; "lembrar de pagar o MEI amanhã" vira tarefa;
  "terminei a ração da Amelie" conclui; "o que tenho amanhã?" responde. Tudo com **Desfazer**.
  - **Listas:** "lista de compras: leite, pão e ovos" vira uma tarefa com subtarefas no Todoist.
  - **Repetição:** "dar o remédio todo dia às 8h" vira tarefa recorrente.
- **Voz:** usa o reconhecimento de voz do aparelho (o mesmo do teclado), que funciona sem IA. Sem esse serviço,
  grava o áudio e a IA transcreve. Se a IA estiver fora, o áudio fica guardado e é processado depois, com notificação.
- **Mover de dia:** no widget, o **»** joga o item para o dia seguinte (atrasada vem para hoje). No app, na aba
  Agenda, segure o item e solte num dia da barra. Recorrentes e convites de outras pessoas ficam de fora.
- **Sugestões 📌 (opcional):** lê notificações de WhatsApp, Telegram, Gmail, Outlook e SMS e sugere só o que parece
  compromisso **seu**. Grupos ficam de fora por padrão; nada é criado sem você confirmar; sugestões vencidas somem.
- **Compartilhar:** em qualquer app, Compartilhar → Consigliere (texto ou print) abre a tela para criar o evento ou
  a tarefa. Também aparece no menu de texto selecionado.
- **Resumos da manhã e da noite:** no app ("Preparar meu dia"), com notificação opcional.
- **Notion (opcional):** procura datas nas páginas compartilhadas com a integração.
- **Tempo:** Open-Meteo, pela cidade digitada ou pela localização aproximada.

## Widgets

- **Meu dia:** saudação com o mascote, conselho do dia, lista Hoje/Semana/Mês agrupada por dia, ↻ e a barra
  "Escreva ou fale". Fundos: **Noir**, **Creme**, paisagens geradas (mudam com o tempo e a hora), obras de arte
  do Met Museum (domínio público, por estilo), caderno e post-it.
- **Conversa rápida:** barra no estilo da busca do Google: mascote, "Consigliere", microfone e atalho para o app.
  Estreito, vira só o botão do microfone.

O widget atualiza a cada 30 min, quando a agenda muda e pelo ↻ (que também troca a arte).

## Inteligência: dois modos

- **Automático (sem IA):** regras no próprio celular. Entendem "amanhã às 9h", "sexta 14:30", "dia 21/10",
  "8 da noite", listas com ":" e vírgulas… Grátis e nada sai do aparelho.
- **Minha IA:** **Gemini**, **Claude** ou **ChatGPT** com a sua chave. O app chama a API direto do celular,
  sem servidor no meio.
  - **Modelo principal** (conversa, áudio, prints) e **modelo leve** (resumo, briefings, notificações, Notion).
    Vazio = automático: no Gemini, o Flash-Lite mais novo da conta (cota grátis bem maior); no Claude, o Haiku.
  - Limite atingido (429): a IA daquele modelo pausa sozinha e o app usa as regras até a cota voltar.
    Sobrecarga (503) tem uma nova tentativa automática.
  - Chaves: aistudio.google.com (Gemini, tem cota grátis), console.anthropic.com (Claude), platform.openai.com.

## Instalar (APK de teste)

1. Cada push na `main` gera o APK pelo GitHub Actions e publica em
   [Releases → latest](https://github.com/trevisollinux/conselheiro-ia/releases/latest) (`Consigliere-<versão>.apk`).
2. No celular, baixe e instale por cima da versão anterior (permita "instalar apps desconhecidos").
3. No primeiro uso, as boas-vindas guiam agenda, Todoist, IA e colocam o widget na tela inicial.
4. Para o widget atualizar sozinho: Configurações → Avançado → Bateria → "sem restrições".

**Todoist:** Configurações → Integrações → Desenvolvedor → copie o **Token da API**.

**Levar para outro aparelho:** Configurações → Avançado → **Exportar (QR)** num aparelho e **Importar** no outro.
O QR contém o token do Todoist e a chave de IA: trate como senha.

## Play Store

- O build de loja (`bundleRelease`, `.aab`) é assinado com a chave de upload guardada nos segredos do GitHub
  (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`) e sai como
  artefato do workflow. Sem os segredos, o CI gera só o APK de teste.
- A chave em `app/agenda.keystore` é pública (repositório aberto) e serve só para o APK de teste; nunca a use na loja.
- Política de privacidade: [`docs/privacidade.html`](docs/privacidade.html), publicada pelo GitHub Pages
  (Settings → Pages → `main` / `docs`).
- Já atende às exigências da loja: aviso antes de ler notificações e botão ⚑ para denunciar respostas da IA.

## Estrutura

`app/src/main/java/com/trevisolli/agendawidget/`

- `MainActivity.kt`: abas Hoje, Agenda e Conversa; configurações (Essencial, Personalizar, Avançado, Sobre)
- `ChatActivity.kt`, `VoiceActivity.kt`, `ConfirmActivity.kt`: conversa por cima da tela inicial, gravador, revisão de sugestões e compartilhamentos
- `ui/`: tema da marca (`Theme.kt`), boas-vindas (`Onboarding.kt`), aba Agenda com arrastar (`AgendaTab.kt`), conversa (`ChatPanel.kt`)
- `data/`: agenda do celular (`CalendarSource`, `CalendarWriter`), Todoist, mover de dia (`Mover`), tempo, arte do Met, configurações
- `smart/`: IA (`AiClient`, `LightModel`), assistente (`Assistant`), regras em português (`RuleParser`), resumo, briefings,
  leitura de notificações, Notion, voz (`VoiceRecorder`, `VoiceQueue`)
- `widget/`: widgets em Jetpack Glance (`AgendaWidget`, `VoiceWidget`, `ChatBar`), fundos e paisagens, atualização (`RefreshWorker`)
- `tools/fetch_art.py`: baixa a coleção de arte de reserva usada quando o Met está fora do ar
