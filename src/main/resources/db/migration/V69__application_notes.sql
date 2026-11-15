-- Notas e marcadores internos na candidatura. B.4 do `plano-growth.md`.
--
-- O que isto resolve: hoje o employer tem etapa (shortlisted, not a fit) e a
-- leitura do modelo, e não tem onde escrever a única coisa que nenhuma das
-- duas carrega -- o motivo. "Boa, mas pediu acima do orçamento", "falar de
-- novo em janeiro", "é a terceira vez que se candidata". Sem lugar para isso,
-- vai para um bloco de notas fora daqui, e o que estava no bloco de notas é o
-- que falta quando se volta à lista três semanas depois.
--
-- **Duas tabelas e não uma coluna de texto na candidatura.** Uma nota tem
-- data: "pediu 90k" escrito em outubro e "pediu 90k" escrito ontem não são a
-- mesma informação, e uma coluna que se sobrescreve perde justamente a parte
-- que faz a nota valer alguma coisa. O marcador é o contrário -- é um
-- conjunto, não um diário -- e por isso é a outra tabela.
--
-- **Isto nunca chega ao candidato.** Não é "não mostrado na tela": é uma
-- tabela que nenhuma consulta do lado do candidato toca, e a chave estrangeira
-- de employer está aqui para que a condição de dono caiba na própria
-- instrução. A regra é a mesma do `stageForCandidate`, um passo mais forte:
-- anotação que se transmite é anotação que ninguém escreve, e esta é a mais
-- franca de todas.

create table application_notes (
    id             bigserial primary key,
    application_id bigint not null references job_applications(id) on delete cascade,
    employer_id    bigint not null references employers(id) on delete cascade,

    body           text not null,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

-- A ordem em que a tela lê: as da candidatura, mais recente primeiro.
create index application_notes_by_application
    on application_notes (application_id, created_at desc);

create table application_tags (
    application_id bigint not null references job_applications(id) on delete cascade,
    employer_id    bigint not null references employers(id) on delete cascade,

    /*
     * O marcador como o employer o escreveu, e a forma normalizada ao lado.
     *
     * Guardar as duas é o que permite "Senior" e "senior" serem o mesmo
     * marcador sem transformar o que a pessoa digitou. A chave primária é a
     * normalizada: uma lista onde `Backend` e `backend` convivem é uma lista
     * que não agrupa nada, que é a única coisa que um marcador faz.
     */
    tag            text not null,
    tag_key        text not null,

    created_at     timestamptz not null default now(),

    primary key (application_id, tag_key)
);

-- O vocabulário do employer: o que ele já usou, para oferecer em vez de pedir
-- que digite de novo -- e é digitando de novo que nasce o terceiro jeito de
-- escrever a mesma palavra.
create index application_tags_by_employer
    on application_tags (employer_id, tag_key);
