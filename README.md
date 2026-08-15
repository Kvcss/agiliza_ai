# Agiliza Cotação — MVP Android

Aplicativo Android nativo para capturar as quatro tabelas reais do ION Vendas, gerar um Excel de preços, preencher um modelo de cotação por caixa e auxiliar o preenchimento de um portal.

## O que já funciona

- Captura assistida e iniciada pelo usuário via `AccessibilityService`.
- Serviço limitado ao pacote `br.com.ionsistemas.ionvendas`.
- Automação restrita ao fluxo **Novo Pedido > Plano de pagamento > Tabela**.
- Seleção automática dos planos **7, 14, 21 e 28 dias**, sem estimar juros.
- Abertura de cada produto somente para leitura dos campos reais do ION: produto, código, EAN da lista, embalagem, unidade, estoque, `Cx Unit/Tot` e `Pço Tabela`.
- Retorno do detalhe exclusivamente com o botão **Voltar** do Android. A captura não toca em **Salvar**, no botão verde, em preço mínimo/tabela/máximo, quantidade, valor ou menus.
- Rolagem automática até o final de cada um dos quatro prazos, deduplicação e proteção contra captura interrompida/parcial.
- Sobreposição com prazo atual, quantidade lida, total de produtos e comandos de pausar/fechar. Ela só existe durante uma sessão de captura e é removida ao concluir, cancelar ou fechar o Agiliza.
- Exportação `.xlsx` com as abas `Cotação`, `Pendências` e `Metadados`.
- Colunas identificadas com os preços por caixa efetivamente lidos em 7, 14, 21 e 28 dias, unidade de venda, unidades por caixa e estoque.
- Somente produtos com estoque numérico maior ou igual a 3 entram na base; estoque menor, ausente ou ilegível é descartado.
- Modalidade **Preencher modelo Excel por caixa**: preserva o arquivo original, renomeia exclusivamente o cabeçalho `nome` para `Vila`, cruza nomes de forma conservadora, prioriza a variante comercial `CJ` e grava o `Pço Tabela` diretamente, sem multiplicação adicional. Produtos não encontrados ou ambíguos ficam sem preenchimento.
- O modelo escolhido é validado antes da geração. A tela confirma nome, tamanho, aba e célula do cabeçalho `nome`; uma cópia errada ou já preenchida é recusada sem tocar nas demais colunas.
- Portal HTTPS dentro de WebView restrito ao domínio/porta configurados.
- Credenciais cifradas com AES-GCM e chave armazenada no Android Keystore.
- Preenchimento por correspondência de EAN com revisão e envio final manuais.

## Como usar

1. Instale o APK e abra **Agiliza Cotação**.
2. Toque em **Configurar permissão de captura**, leia o aviso e habilite o serviço.
3. Em cada modalidade, escolha qual base capturada será usada: **7, 14, 21 ou 28 dias**. O fornecedor do modelo Excel é fixo como **Vila Nova**.
4. Toque em **Abrir Ion e capturar**.
5. No ION, siga: **Clientes > qualquer cliente > carrinho > Novo Pedido** e permaneça na aba **Cabeçalho**.
6. Na sobreposição do Agiliza, toque em **Capturar 4 prazos** e aguarde. Não interaja com o ION durante a execução.
7. Volte ao Agiliza, revise as linhas e exporte o Excel.
8. Para a nova modalidade, toque em **Selecionar Excel da cotação** e depois em **Gerar Excel preenchido**.
9. Para o portal, informe o link HTTPS e os seletores CSS do portal real. Abra, entre e toque em **Preencher**; revise antes de enviar.

## Compilar

Requisitos: JDK 17 e Android SDK 36.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Limites do MVP

- O preenchimento do site só pode ser calibrado completamente com a URL/HTML ou uma conta de homologação.
- Portais com paginação, tabela virtual, iframe, CAPTCHA ou MFA exigem um adaptador específico.
- A automação foi mapeada para os identificadores do ION Vendas 1.26.x. Uma atualização do ION que renomeie telas/campos pode exigir ajuste.
- O preenchimento por nome é deliberadamente conservador: uma descrição duvidosa fica vazia para evitar preço no produto errado.
- O APK atual é de desenvolvimento e assinado com a chave de debug. Para distribuição, crie uma chave de release, política de privacidade e valide autorização da empresa/fornecedor.
- Não desative o Play Protect. Para uso interno, prefira distribuição corporativa gerenciada.

## Segurança e integridade

- O app não acessa o banco privado do Ion e não tenta contornar proteções do Android.
- Rascunhos parciais ficam protegidos e podem ser exportados; a última base útil continua disponível se a nova coleta ainda não tiver item elegível.
- Arquivos XLSX são montados antes da gravação e salvos com truncamento explícito, evitando cauda de ZIP antiga ao substituir um documento existente.
- Backup do aplicativo está desativado.
- Nenhuma senha entra no Excel ou nos logs do aplicativo.
- O envio final da cotação permanece sob confirmação do usuário.
