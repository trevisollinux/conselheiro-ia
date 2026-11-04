# Consigliere

Seu conselheiro pessoal no Android: agenda e tarefas juntas num widget, um resumo do que importa no dia
e um assistente que anota por você, por texto ou voz.

O repositório também tem o **[Unusual Stickers](#unusual-stickers-app-de-figurinhas)**, um app separado para criar
figurinhas do WhatsApp sem anúncios.

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

## Unusual Stickers (app de figurinhas)

Um segundo app, no módulo `sticker/` (antes chamado Conselheiro Sticker): cria pacotes de figurinhas para o WhatsApp, **sem anúncios e sem cobrança**.
Tudo fica no celular, sem conta e sem servidor.

- **Pacotes:** de 3 a 30 figurinhas, com nome, autor e ícone gerado da primeira figurinha. **Adicionar ao WhatsApp**
  (ou WhatsApp Business) pela integração oficial; depois de editar, toque de novo para atualizar.
- **Recortar:** **retângulo** com alças nos cantos e lados (livre, quadrado ou círculo),
  ✨ recorte automático do fundo (ML Kit, no aparelho), **laço** (contorne o que fica),
  **borracha** e **pincel** (devolve o que foi apagado), com Desfazer.
- **Ajustar:** mover, zoom e giro com os dedos; borda em volta da figurinha: **Sem borda**, **Fina**, **Média** ou
  **Grossa** (com cor e espessura). O app lembra a última escolha para as próximas figurinhas.
- **Texto de meme:** vários textos, arrastar, pinça para tamanho e giro, cor da letra e da borda, CAIXA ALTA.
  Fontes livres embutidas: Anton (parecida com a Impact), Bebas Neue, Archivo Black, Bangers, Luckiest Guy,
  Comic Neue e Permanent Marker. **Importar fonte** aceita qualquer `.ttf`/`.otf` (a Impact é da Microsoft e
  não pode vir no app, mas dá para importar a sua).
- **Só texto:** figurinha com fundo transparente.
- **Compartilhar:** em qualquer app, Compartilhar uma foto → Unusual Stickers.
- Toda figurinha guarda a foto original e os textos: **Editar** reabre tudo editável.
- **Backup sem cadastro:** exporta os pacotes num `.zip` e importa de volta (detalhes abaixo).

Formato que o WhatsApp exige (o app cuida sozinho): WebP 512×512 com até 100 KB; ícone PNG 96×96.

### Como usar

1. **Novo pacote** → dê um nome e o autor (o autor fica lembrado para os próximos).
2. **+ Figurinha** → **Foto da galeria** ou **Só texto**.
3. Aba **Recortar**: a foto abre inteira, com a moldura de corte. Arraste os cantos e os lados (ou o meio, para mover),
   escolha **Livre**, **Quadrado** ou **Círculo** e toque **✂️ Cortar**. Se preferir, use **✨ Automático** (tira o fundo),
   **Laço** (contorne o que fica), **Borracha** e **Pincel**; **Desfazer** no topo e **Recomeçar** volta à foto inteira.
4. Aba **Ajustar**: arraste e use dois dedos para posicionar; ligue o **contorno de adesivo**; escolha os emojis
   (até 3, usados na busca do WhatsApp).
5. Aba **Texto**: **+ Texto** (o primeiro vai em cima, o segundo embaixo). Toque num texto para selecionar, arraste
   para mover, pinça para tamanho e giro. Troque fonte, cores, borda e CAIXA ALTA.
6. **Salvar**. Com 3 figurinhas ou mais, **Adicionar ao WhatsApp**. Depois de mudar algo, toque de novo para atualizar.

Na grade do pacote, tocar numa figurinha abre **Editar**, **Emojis**, **Mover** (muda a ordem; a primeira vira o
ícone) e **Apagar**.

### Backup (sem conta)

- **Exportar todos:** na tela inicial, menu **⋮ → Exportar todos os pacotes**. **Um pacote:** dentro dele, botão de
  compartilhar no topo. O Android pergunta onde salvar o `.zip`: Google Drive, Downloads, pendrive…
- **Importar:** menu **⋮ → Importar backup (.zip)**. Um pacote com o mesmo identificador é substituído pelo do arquivo;
  os outros continuam como estão. Serve também para passar um pacote para outra pessoa que tenha o app.
- O `.zip` leva as figurinhas prontas e os dados para reeditar (foto original, recorte e textos).
- **Backup automático do Android:** também fica ligado e salva as figurinhas prontas na conta Google do celular
  (limite de 25 MB do Android). Os dados de reedição, mais pesados, ficam de fora; para eles, use o Exportar.

### Apoie 💜

O Unusual Stickers é grátis e sem anúncios. Gostou do app? Mande qualquer valor via **Pix**:
**gustavotrevisolli@gmail.com** (também no app: menu **⋮ → Apoiar o app**, com botão para copiar a chave).

### Limitações (por enquanto)

- Só figurinhas paradas; animadas (GIF/vídeo) ainda não.
- O recorte automático usa o Google Play services: na primeira vez o modelo pode demorar a baixar. Sem ele, o laço
  e a borracha funcionam normalmente.
- O backup é manual (Exportar); o app não envia nada sozinho para a nuvem além do backup automático do Android.

**Instalar:** cada push que mexe em `sticker/` gera o APK no GitHub Actions (workflow *Build Unusual Stickers*,
artefato `unusual-stickers-apk`); na `main`, também sai uma release `sticker-v…` com `UnusualStickers-<versão>.apk`.

Código em `sticker/src/main/java/com/trevisolli/conselheirosticker/`: `data/` (pacotes e WebP), `editor/`
(recorte, textos, desenho da figurinha), `ui/` (telas), `whatsapp/` (ContentProvider e intent oficial).
