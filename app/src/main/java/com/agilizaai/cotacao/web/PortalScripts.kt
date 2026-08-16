package com.agilizaai.cotacao.web

import com.agilizaai.cotacao.data.AppPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scripts injetados no portal.
 *
 * As colunas são descobertas pelos títulos da própria tabela ("Código", "Código
 * Barra", "Descrição", "Custo") em vez de seletores CSS fixos: o portal é gerado
 * por um sistema de terceiros e os nomes internos das classes podem mudar a
 * qualquer atualização. Os seletores configurados continuam existindo como
 * segunda tentativa.
 *
 * Nenhum script procura, aciona ou submete o botão Salvar da cotação.
 */
internal object PortalScripts {

    /** Rotinas comuns a todos os scripts. */
    private val PRELUDE = """
        const HEADER_SCAN_ROWS = 5;
        const norm = (value) => String(value == null ? '' : value)
          .normalize('NFD')
          .replace(/[^\x20-\x7E]/g, '')
          .replace(/\s+/g, ' ')
          .trim()
          .toLowerCase();
        const visible = (element) => {
          if (!element || !element.isConnected || element.disabled) return false;
          const style = getComputedStyle(element);
          if (style.display === 'none' || style.visibility === 'hidden') return false;
          const rect = element.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0;
        };
        // A ordem importa: "Código Barra" contém "barra" e tem de virar barcode antes
        // de virar code, e "Código Produto" tem de virar code — nunca description por
        // conter a palavra "produto".
        const roleOf = (title) => {
          const text = norm(title);
          if (!text) return null;
          if (text.includes('barra') || text === 'ean' || text.includes('gtin')) return 'barcode';
          if (text.startsWith('codigo') || text.startsWith('cod') || text === 'cd') return 'code';
          if (text.includes('descri') || text.includes('produto') || text.includes('mercadoria')) return 'description';
          if (costRank(text) > 0) return 'cost';
          return null;
        };
        // Uma cotação costuma ter mais de uma coluna numérica ("Qtd", "Preço venda",
        // "Custo"). O campo que o portal espera é o de custo: ele ganha das demais.
        const costRank = (text) => {
          if (text.includes('custo')) return 3;
          if (text.includes('unitario') || text.includes('unit')) return 2;
          if (text.includes('preco') || text.includes('pco') || text.includes('valor')) return 1;
          return 0;
        };
        // Lê um candidato a linha de cabeçalho e diz quantas colunas úteis ele revela.
        const readHeader = (row) => {
          const map = {};
          const costColumns = [];
          Array.from(row.children || []).forEach((cell, position) => {
            const role = roleOf(cell.textContent);
            if (!role) return;
            if (role === 'cost') {
              costColumns.push({ position: position, rank: costRank(norm(cell.textContent)) });
              return;
            }
            if (map[role] === undefined) map[role] = position;
          });
          const score = ['barcode', 'code', 'description']
            .filter((role) => map[role] !== undefined).length;
          return { map: map, costColumns: costColumns, score: score };
        };
        /**
         * Procura as tabelas da cotação: cada uma precisa de uma coluna de custo com
         * campo editável e de alguma coluna que identifique o produto.
         *
         * O cabeçalho não é assumido como a primeira linha. A cotação abre com uma
         * faixa de grupo ("LOJA BAZAR GERAL") acima dos títulos, e tomá-la como
         * cabeçalho não encontraria coluna nenhuma. As primeiras linhas são avaliadas
         * e vence a que revela mais colunas conhecidas.
         */
        const findTables = () => {
          const results = [];
          Array.from(document.querySelectorAll('table')).forEach((table) => {
            const candidates = Array.from(table.querySelectorAll('thead tr'))
              .concat(Array.from(table.querySelectorAll('tr')).slice(0, HEADER_SCAN_ROWS));
            let header = null;
            candidates.forEach((row) => {
              const parsed = readHeader(row);
              if (!parsed.costColumns.length || parsed.score === 0) return;
              if (!header || parsed.score > header.score) header = parsed;
            });
            if (!header) return;
            const bodyRows = Array.from(table.querySelectorAll('tbody tr'));
            const allRows = bodyRows.length ? bodyRows : Array.from(table.querySelectorAll('tr'));
            // "Custo" ganha de "Preço"/"Valor", mas só vale a coluna que realmente
            // tem campo editável — senão o preenchimento não teria onde escrever.
            header.costColumns.sort((a, b) => b.rank - a.rank);
            for (const candidate of header.costColumns) {
              const rows = allRows.filter((row) => {
                const cells = row.children;
                if (!cells || cells.length <= candidate.position) return false;
                return Boolean(cells[candidate.position].querySelector('input, textarea'));
              });
              if (!rows.length) continue;
              results.push({
                table: table,
                map: Object.assign({}, header.map, { cost: candidate.position }),
                rows: rows
              });
              break;
            }
          });
          // Uma tabela de layout que envolve a tabela real casa com as mesmas linhas.
          // Manter as duas contaria e preencheria cada produto duas vezes.
          return results.filter((entry, position) => !results.some(
            (other, otherPosition) => otherPosition !== position &&
              entry.table !== other.table &&
              entry.table.contains(other.table)
          ));
        };
        const cellText = (row, position) => {
          if (position === undefined) return '';
          const cell = row.children[position];
          if (!cell) return '';
          if (cell.dataset && cell.dataset.barcode) return String(cell.dataset.barcode).trim();
          const text = String(cell.textContent || '').trim();
          if (text) return text;
          // Alguns portais mostram código e descrição dentro de campos somente leitura,
          // e aí textContent vem vazio.
          const field = cell.querySelector('input, textarea, select');
          return field ? String(field.value || '').trim() : '';
        };
        const one = (selector, root) => {
          try { return (root || document).querySelector(selector); } catch (_) { return null; }
        };
        const all = (selector, root) => {
          try { return Array.from((root || document).querySelectorAll(selector)); } catch (_) { return []; }
        };
        const TEXT_INPUT_TYPES = ['text', 'email', 'tel', 'number', 'search', ''];
        const isTextInput = (element) =>
          element && element.tagName === 'INPUT' &&
          TEXT_INPUT_TYPES.indexOf(String(element.type || '').toLowerCase()) >= 0;
        const labelOf = (element) => norm(
          element.value || element.textContent || element.getAttribute('aria-label') || element.title || ''
        );
        const SUBMIT_WORDS = ['entrar', 'login', 'logar', 'acessar', 'acesso', 'conectar', 'continuar', 'confirmar', 'sign in', 'ok'];
        /**
         * Encontra o formulário de login pela estrutura da página.
         *
         * O portal VR Cotação rotula os campos como "Código" e "Senha" e envia por um
         * botão "Entrar" — nenhum deles casa com seletores pensados para e-mail. A
         * âncora aqui é o campo de senha visível: dele se sobe até o container que
         * também tem o campo de texto do código, e o botão é achado pelo rótulo.
         * Os seletores configurados continuam tendo prioridade quando funcionam.
         */
        const findLogin = (userSelector, passSelector, submitSelector) => {
          let pass = one(passSelector);
          if (!visible(pass)) pass = null;
          if (!pass) pass = all('input[type=password]').filter(visible)[0] || null;
          if (!pass) return null;

          let scope = pass.form;
          if (!scope) {
            let node = pass.parentElement;
            while (node && node !== document.body) {
              const siblings = all('input', node).filter(visible);
              if (siblings.some((el) => el !== pass && isTextInput(el))) break;
              node = node.parentElement;
            }
            scope = node || document.body;
          }

          let user = one(userSelector, scope) || one(userSelector);
          if (!visible(user) || user === pass || !isTextInput(user)) user = null;
          if (!user) {
            const textual = all('input', scope).filter((el) => visible(el) && isTextInput(el) && el !== pass);
            const before = textual.filter(
              (el) => pass.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_PRECEDING
            );
            user = before[before.length - 1] || textual[0] || null;
          }
          if (!user) return null;

          let submit = one(submitSelector, scope);
          if (!visible(submit)) submit = null;
          if (!submit) {
            const scoped = all('button, input[type=submit], input[type=button], input[type=image], a[href]', scope);
            const page = all('button, input[type=submit], input[type=button], input[type=image]');
            const buttons = scoped.concat(page).filter(visible);
            submit = buttons.filter((el) => String(el.type || '').toLowerCase() === 'submit')[0]
              || buttons.filter((el) => SUBMIT_WORDS.some((word) => labelOf(el).includes(word)))[0]
              || buttons[0]
              || null;
          }
          // O botão é opcional: um formulário clássico ainda pode ser enviado sozinho.
          return { user: user, pass: pass, submit: submit, form: pass.form || null };
        };
        /**
         * Converte o que o campo mostra para um número comparável.
         *
         * O portal reformata o valor no padrão brasileiro ("1.234,56"). Tratar o ponto
         * como decimal marcaria como recusado um preço que foi aceito corretamente.
         */
        const toNumberText = (value) => {
          const cleaned = String(value == null ? '' : value).trim().replace(/[^0-9,.-]/g, '');
          if (cleaned.indexOf(',') >= 0) return cleaned.replace(/\./g, '').replace(',', '.');
          return cleaned;
        };
        const setValue = (element, value) => {
          const proto = element instanceof HTMLTextAreaElement
            ? HTMLTextAreaElement.prototype
            : HTMLInputElement.prototype;
          const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
          if (setter) setter.call(element, value); else element.value = value;
          element.dispatchEvent(new Event('input', { bubbles: true }));
          element.dispatchEvent(new Event('change', { bubbles: true }));
          element.dispatchEvent(new Event('blur', { bubbles: true }));
        };
    """.trimIndent()

    /** Detecta se estamos no login ou já na tabela, sem alterar nada. */
    fun probe(preferences: AppPreferences): String = wrap(
        """
        const found = findTables();
        const total = found.reduce((sum, group) => sum + group.rows.length, 0);
        const tableReady = total > 0;
        const form = tableReady ? null : findLogin(${selectors(preferences)});
        const loginReady = Boolean(form);
        return JSON.stringify({ ok: true, loginReady, tableReady, rows: total });
        """.trimIndent()
    )

    fun login(preferences: AppPreferences, code: String, password: String): String = wrap(
        """
        const form = findLogin(${selectors(preferences)});
        if (!form) {
          return JSON.stringify({ ok: false, message: 'Não encontrei os campos Código e Senha na página' });
        }
        setValue(form.user, ${json(code)});
        setValue(form.pass, ${json(password)});
        // Sem botão reconhecível o formulário ainda é enviado pelo próprio elemento,
        // que é como a página de login do portal funciona quando o Entrar é uma imagem.
        if (form.submit) {
          form.submit.click();
        } else if (form.form && typeof form.form.requestSubmit === 'function') {
          form.form.requestSubmit();
        } else if (form.form) {
          form.form.submit();
        } else {
          return JSON.stringify({ ok: false, message: 'Não encontrei o botão Entrar do portal' });
        }
        return JSON.stringify({ ok: true, message: 'Login enviado' });
        """.trimIndent()
    )

    private fun selectors(preferences: AppPreferences): String = listOf(
        preferences.loginUserSelector,
        preferences.loginPasswordSelector,
        preferences.loginSubmitSelector
    ).joinToString(", ") { json(it) }

    /**
     * Lê código, EAN e descrição de cada linha e marca a linha com um índice estável,
     * para que o preenchimento em lotes escreva sempre no campo certo.
     */
    fun readRows(): String = wrap(
        """
        const found = findTables();
        if (!found.length) return JSON.stringify({ ok: false, message: 'Tabela da cotação não encontrada' });
        const rows = [];
        let index = 0;
        found.forEach((group) => {
          group.rows.forEach((row) => {
            row.setAttribute('data-agiliza-row', String(index));
            // A coluna de custo vai gravada na própria linha: uma cotação dividida em
            // grupos pode ter tabelas com colunas em posições diferentes, e o lote de
            // preenchimento não precisa reencontrar a tabela para saber onde escrever.
            row.setAttribute('data-agiliza-cost', String(group.map.cost));
            const barcodeCell = cellText(row, group.map.barcode);
            const digits = (barcodeCell.match(/\d/g) || []).join('');
            let barcode = digits;
            if (group.map.barcode === undefined) {
              const scan = (row.textContent || '').match(/\d{8,14}/);
              barcode = scan ? scan[0] : '';
            }
            rows.push({
              index: index,
              code: cellText(row, group.map.code),
              barcode: barcode,
              description: cellText(row, group.map.description)
            });
            index++;
          });
        });
        return JSON.stringify({ ok: true, rows: rows, groups: found.length });
        """.trimIndent()
    )

    /**
     * Escreve um lote de preços e confere o valor que o campo realmente aceitou.
     * Verde = confirmado pelo portal; laranja = o campo recusou ou reformatou.
     */
    fun fillBatch(batch: List<PortalMatcher.Resolution>): String {
        val payload = JSONArray().apply {
            batch.forEach { resolution ->
                put(
                    JSONObject()
                        .put("index", resolution.rowIndex)
                        .put("price", resolution.price)
                )
            }
        }
        return wrap(
            """
            const items = $payload;
            let confirmed = 0, rejected = 0;
            for (const item of items) {
              const row = document.querySelector('[data-agiliza-row="' + item.index + '"]');
              if (!row) { rejected++; continue; }
              const costIndex = Number(row.getAttribute('data-agiliza-cost'));
              const cell = Number.isFinite(costIndex) ? row.children[costIndex] : null;
              const input = cell ? cell.querySelector('input, textarea') : null;
              if (!input || input.disabled || input.readOnly) { rejected++; continue; }
              const raw = String(item.price);
              const value = String(input.type).toLowerCase() === 'number' ? raw : raw.replace('.', ',');
              setValue(input, value);
              const actual = toNumberText(input.value);
              const expected = raw.replace(',', '.');
              if (Number.isFinite(Number(actual)) && Math.abs(Number(actual) - Number(expected)) < 0.005) {
                confirmed++;
                input.style.outline = '3px solid #12805C';
                input.style.outlineOffset = '2px';
              } else {
                rejected++;
                input.style.outline = '3px solid #B45309';
                input.style.outlineOffset = '2px';
              }
            }
            return JSON.stringify({ ok: true, confirmed: confirmed, rejected: rejected });
            """.trimIndent()
        )
    }

    private fun wrap(body: String): String = """
        (() => {
          try {
        $PRELUDE
        $body
          } catch (error) {
            return JSON.stringify({ ok: false, message: 'Erro ao ler a página: ' + error });
          }
        })();
    """.trimIndent()

    private fun json(value: String): String = JSONObject.quote(value)
}
