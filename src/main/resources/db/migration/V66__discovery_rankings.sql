-- A descoberta de perfis por vaga, na parte que custa modelo.
--
-- O C.2 do `plano-growth.md` parte a descoberta em dois: um filtro
-- determinístico sobre campos estruturados, que é de graça e já está no ar, e
-- uma reordenação pelo modelo dos primeiros. Estas duas tabelas são a segunda
-- metade, e a razão de existirem como tabela em vez de chamada ao vivo é a
-- mesma do `application_screenings`: uma chamada de modelo dentro de uma
-- requisição HTTP é uma requisição que trava, não tem como tentar de novo, e
-- não deixa registro do que custou.
--
-- **Quem escolhe os candidatos é o site, não o pipeline.** O filtro -- quem
-- pode trabalhar onde, que contrato, quais palavras da vaga -- mora no
-- TypeScript, em `lib/discovery-fit.ts`, e é uma regra sobre quem pode ser
-- mostrado a um employer. Duas cópias dela seriam duas chances de divergir, e
-- a que divergisse em silêncio seria a do lado que ninguém está olhando.
-- Então o site grava as linhas de `discovery_ranking_entries` já escolhidas, e
-- o pipeline só pontua o que recebeu.

create table discovery_rankings (
    id              bigserial primary key,
    employer_id     bigint not null references employers(id) on delete cascade,
    posting_id      bigint not null references employer_postings(id) on delete cascade,

    -- Preso à versão do texto da vaga, como os requisitos já são. Editar a
    -- vaga não reescreve em silêncio uma lista já gerada: cria a possibilidade
    -- de outra.
    content_version integer not null,

    /*
     * Isto é a unidade da cota, e é por isso que ela se conta em vez de se
     * guardar.
     *
     * Decisão de 7 Nov 2026: cinco gerações por mês no Growth, mensais mesmo
     * em quem paga anual, mais pacotes. O saldo é `5 + pacotes - linhas do
     * mês`, uma consulta sobre esta tabela. Saldo guardado em coluna é saldo
     * que diverge do que de fato aconteceu -- e divergir aqui é cobrar de
     * alguém uma geração que não houve, ou dar de graça as que houve.
     */
    requested_at    timestamptz not null default now(),
    finished_at     timestamptz,

    state           text not null default 'PENDING'
                    check (state in ('PENDING', 'OK', 'FAILED')),
    failure         text,
    attempts        integer not null default 0,

    /*
     * Como esta lista foi montada, e por que a coluna existe.
     *
     * `VECTOR` é o caminho bom: o resumo da vaga e o do candidato comparados
     * como vetores. `WORDS` é o antigo, por palavras em comum, e ele continua
     * existindo porque uma vaga recém-escrita ainda não tem vetor -- o
     * indexador leva alguns minutos -- e lista pior é melhor que lista
     * nenhuma.
     *
     * Guardado por linha e não deduzido depois porque é o que permite
     * perguntar, daqui a um mês, se as listas que o employer achou ruins eram
     * as que caíram no caminho antigo.
     */
    method          text not null default 'WORDS'
                    check (method in ('WORDS', 'VECTOR'))
);

-- A fila que o pipeline varre.
create index discovery_rankings_pending
    on discovery_rankings (requested_at) where state = 'PENDING';

-- A cota do mês e a espera de sete dias por vaga saem daqui.
create index discovery_rankings_by_employer
    on discovery_rankings (employer_id, requested_at desc);

-- A lista corrente de uma vaga.
create index discovery_rankings_by_posting
    on discovery_rankings (posting_id, requested_at desc);

-- Um perfil dentro de uma geração.
create table discovery_ranking_entries (
    id              bigserial primary key,
    ranking_id      bigint not null references discovery_rankings(id) on delete cascade,
    candidate_id    bigint not null references candidates(id) on delete cascade,

    /*
     * Qual CV foi lido, e pode ser nulo.
     *
     * Pode ser o da conta ou o que a pessoa anexou a esta vaga -- decisão do
     * Arthur em 7 Nov 2026, de que a descoberta procura nos dois lugares. Uma
     * linha por candidato, não por documento: o C de `plano-growth.md` manda
     * ficar o melhor CV de cada pessoa e nunca o segundo melhor da mesma.
     *
     * Medido em produção no mesmo dia, e é o que torna essa escolha barata:
     * 300 CVs de perfil contra 3 de candidatura. Pontuar todos os CVs de
     * alguém para escolher o melhor custaria uma chamada de modelo por
     * documento e hoje não mudaria uma única linha desta tabela.
     *
     * Nulo quando a pessoa não tem CV nenhum: ela continua na lista, pontuada
     * pelo perfil, porque sumir dela seria dizer que não existe.
     */
    resume_id       bigint references candidate_resumes(id) on delete set null,

    -- A posição que o filtro determinístico deu, antes de qualquer modelo.
    -- Guardada para que se possa ver depois o que a reordenação mudou -- e se
    -- ela mudou alguma coisa.
    seed_rank       integer not null,

    /*
     * A nota de 0 a 10, calculada aqui e não pedida ao modelo, exatamente como
     * em `application_screenings`: ela sai dos vereditos por uma fórmula que
     * mora no código, então é reproduzível e explicável. Um número inventado
     * pelo modelo não é comparável entre dois perfis nem entre duas leituras
     * do mesmo.
     */
    score           numeric(3,1),

    -- A frase curta que o employer lê no cartão. Esta é do modelo, porque é
    -- julgamento em prosa e não aritmética.
    summary         text,

    state           text not null default 'PENDING'
                    check (state in ('PENDING', 'OK', 'FAILED', 'SKIPPED')),
    failure         text,
    attempts        integer not null default 0,

    model           text,
    tokens_in       integer,
    tokens_out      integer,
    evaluated_at    timestamptz
);

-- Um candidato aparece uma vez por geração. É a regra "um por pessoa, o
-- melhor dela" escrita como constraint em vez de confiada ao escritor.
create unique index discovery_ranking_entries_once
    on discovery_ranking_entries (ranking_id, candidate_id);

create index discovery_ranking_entries_pending
    on discovery_ranking_entries (ranking_id) where state = 'PENDING';
