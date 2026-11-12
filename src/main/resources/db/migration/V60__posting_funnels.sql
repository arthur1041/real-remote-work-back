/*
 * O funil de uma vaga: a regra que o employer monta sobre os requisitos.
 *
 * A triagem responde "onde esta pessoa está em cada requisito". O funil
 * responde a pergunta seguinte, que é a que decide o que abrir primeiro:
 * **"quais desses requisitos, combinados de que jeito, eu de fato exijo?"**
 * Nem todo requisito pesa igual, e a vaga raramente é uma conjunção simples --
 * "(fuso OU disponibilidade declarada) E contractor E NÃO expectativa > 4.000"
 * é a forma real da exigência, e sem poder escrevê-la o employer só tem a
 * nota, que mistura tudo numa média.
 *
 * O resultado não é aprovado/reprovado, são **três grupos** -- atende, atende
 * em parte, não atende -- e o do meio é a razão de existirem três. Ver
 * `web/lib/funnel.ts`, onde a avaliação mora, e a seção A.3 de
 * `docs/plano-growth.md`.
 */
create table posting_funnels (
    id              bigserial primary key,
    posting_id      bigint not null references employer_postings(id) on delete cascade,

    /*
     * Preso à versão do texto, como `posting_requirements` está.
     *
     * A expressão aponta para requisitos, e requisitos pertencem a uma versão
     * do texto da vaga: editar a vaga extrai um conjunto novo, com ids novos.
     * Um funil que sobrevivesse à edição apontaria para requisitos que não
     * existem mais, ou -- pior -- para ids reaproveitados que agora significam
     * outra coisa, e a regra passaria a dizer em silêncio algo que ninguém
     * escreveu. Por isso ele é por versão, e editar a vaga deixa a vaga sem
     * funil até alguém montar o próximo. A tela diz isso em voz alta; herdar
     * o funil anterior casando requisito por texto é melhoria possível depois,
     * e é melhoria que precisa de confirmação humana, nunca automática.
     */
    content_version integer not null,

    /*
     * A expressão, como árvore e não como texto a ser interpretado.
     *
     * `{"op":"AND","terms":[{"op":"OR","terms":[{"req":12},{"req":14}]},
     * {"req":13},{"op":"NOT","term":{"req":15}}]}`
     *
     * Guardar a frase que o employer digitou obrigaria a interpretá-la a cada
     * leitura, e um erro de interpretação aqui não aparece como erro: aparece
     * como uma pessoa no grupo errado. A árvore é validada uma vez, quando é
     * gravada, e depois só é percorrida. `jsonb` e não `json` porque é lida
     * muito mais do que escrita.
     *
     * Um nível de parênteses, imposto na gravação por `web/lib/funnel.ts`: a
     * expressão é para ser lida por quem contrata, não por quem programa, e a
     * segunda camada de aninhamento é onde ela deixa de ser conferível de
     * relance.
     */
    expression      jsonb not null,

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- Um funil por vaga por versão do texto. Dois seriam duas regras verdadeiras
-- ao mesmo tempo, e nada diria qual vale.
create unique index posting_funnels_one
    on posting_funnels (posting_id, content_version);
