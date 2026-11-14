-- Descoberta semântica: o resumo que o modelo escreve e o vetor que o indexa.
--
-- **Por que isto existe.** A primeira descoberta ordenava por palavras em
-- comum entre o título da vaga e as habilidades declaradas. Era barato e
-- explicável, e é ruim: não sabe que `Go` e `Golang` são a mesma coisa, que
-- `K8s` é `Kubernetes`, que quem tem `Django` sabe Python; não lê o CV; e um
-- perfil escrito em português não casa com uma vaga escrita em inglês. Isso
-- não sustenta um plano de 39,90 -- decisão do Arthur em 8 Nov 2026.
--
-- **Três peças, e a ordem importa.** Filtro duro (país, contrato) decide quem
-- *pode*; o vetor decide quem *parece*; o modelo decide quem *serve*. Cada uma
-- corta o custo da seguinte: o filtro é SQL, o vetor é um índice, e só as
-- primeiras dezenas chegam ao modelo.
--
-- **O que é embeddado não é o CV.** É um resumo que o modelo escreve uma vez
-- por CV, e isso não é um detalhe de implementação:
--
--   * o limite do embedder é 2048 tokens por texto, e CV não cabe;
--   * PDF rende cabeçalho, rodapé, telefone e lixo de OCR, que entram no vetor
--     como se fossem competência;
--   * o resumo normaliza sinônimo e idioma -- um CV em português vira texto
--     comparável a uma vaga em inglês, que é o caso de mais da metade desta
--     base;
--   * e ele é reaproveitado no passo do modelo, que então ranqueia sem
--     reenviar trinta PDFs.
--
-- **768 dimensões e não 3072.** `gemini-embedding-001` entrega 3072 por
-- padrão e permite truncar; o treino em matryoshka é o que torna 768 honesto
-- em vez de uma mutilação. 3072 seriam 12KB por candidato e um índice várias
-- vezes maior para um ganho que esta base -- centenas, não milhões -- não
-- consegue nem medir.

create extension if not exists vector;

-- O resumo de um candidato, e o vetor dele.
create table candidate_digests (
    candidate_id    bigint primary key references candidates(id) on delete cascade,

    -- Qual CV foi lido. Nulo é legítimo: quem não tem CV ainda tem perfil, e
    -- some da descoberta só quem pediu para sumir.
    resume_id       bigint references candidate_resumes(id) on delete set null,

    /*
     * O que foi lido, resumido num hash.
     *
     * É o que responde "este resumo ainda vale?" sem reescrever nada: muda o
     * perfil ou o CV, muda o hash, e a fila enxerga a linha como vencida. Sem
     * isto a alternativa é redigerir todo mundo periodicamente, que é pagar
     * modelo por candidato que não mudou.
     */
    fingerprint     text not null,

    -- O resumo em si, em inglês e em prosa curta: cargos, anos, stack com
    -- profundidade, domínios, idiomas, onde pode trabalhar. Guardado em texto
    -- porque é auditável -- dá para ler e discordar -- e porque o passo de
    -- ranqueamento o usa no lugar do PDF.
    summary         text not null,

    embedding       vector(768),

    digest_model    text,
    embed_model     text,
    tokens_in       integer,
    tokens_out      integer,

    state           text not null default 'PENDING'
                    check (state in ('PENDING', 'OK', 'FAILED')),
    failure         text,
    attempts        integer not null default 0,

    requested_at    timestamptz not null default now(),
    digested_at     timestamptz,
    embedded_at     timestamptz
);

create index candidate_digests_pending
    on candidate_digests (requested_at) where state = 'PENDING';

/*
 * O índice da busca. HNSW e não IVFFlat: IVF precisa de dados para treinar as
 * listas e degrada quando a base cresce por cima do treino, e esta base vai
 * crescer uma ordem de grandeza. HNSW custa mais para construir e não precisa
 * de treino nenhum.
 *
 * Cosseno porque os vetores vêm normalizados e a pergunta é de direção, não de
 * magnitude -- um CV longo não deve ganhar de um curto por ser longo.
 */
create index candidate_digests_vec
    on candidate_digests using hnsw (embedding vector_cosine_ops);

-- O mesmo do lado da vaga, preso à versão do texto: editar a vaga é outra
-- pergunta, e a resposta velha continua existindo até alguém pedir outra.
create table posting_digests (
    posting_id      bigint not null references employer_postings(id) on delete cascade,
    content_version integer not null,

    summary         text not null,
    embedding       vector(768),

    digest_model    text,
    embed_model     text,
    tokens_in       integer,
    tokens_out      integer,

    state           text not null default 'PENDING'
                    check (state in ('PENDING', 'OK', 'FAILED')),
    failure         text,
    attempts        integer not null default 0,

    requested_at    timestamptz not null default now(),
    digested_at     timestamptz,
    embedded_at     timestamptz,

    primary key (posting_id, content_version)
);

create index posting_digests_pending
    on posting_digests (requested_at) where state = 'PENDING';

/*
 * Quem deve ter resumo, e qual é a impressão digital do que há para resumir.
 *
 * Uma visão e não uma consulta copiada no Java: "o que conta como mudança" é
 * uma regra, e regra em duas cópias é regra que diverge. O pipeline lê daqui
 * para saber o que redigerir, e a mesma expressão responde pela validade do
 * que já existe.
 *
 * `email_verified_at` entra porque conta não confirmada não é pessoa ainda, e
 * resumir uma é pagar modelo por um endereço que ninguém abriu.
 */
create view candidate_digest_source as
select c.id                                   as candidate_id,
       r.id                                   as resume_id,
       md5(
         coalesce(c.headline, '')        || '|' ||
         coalesce(c.seniority, '')       || '|' ||
         coalesce(c.years_experience::text, '') || '|' ||
         coalesce(array_to_string(c.skills, ','), '')    || '|' ||
         coalesce(array_to_string(c.languages, ','), '') || '|' ||
         coalesce(c.timezone, '')        || '|' ||
         coalesce(c.country, '')         || '|' ||
         coalesce(array_to_string(c.employment_kinds, ','), '') || '|' ||
         coalesce(r.id::text, '')
       )                                      as fingerprint
  from candidates c
  left join candidate_resumes r
         on r.candidate_id = c.id
        and r.scope = 'PROFILE'
        and r.replaced_at is null
 where c.status = 'ACTIVE'
   and c.discoverable
   and c.email_verified_at is not null
   /*
    * E há o que resumir.
    *
    * Medido na primeira rodada real, em 8 Nov 2026: perfis sem CV, sem
    * headline e sem habilidades fizeram o modelo escrever 120 palavras
    * dizendo que não havia informação -- e texto assim embeda perto de outro
    * texto assim, de modo que o índice ganha um bolsão de gente sem dado
    * nenhum que casa consigo mesmo. Custou chamada de modelo para piorar a
    * busca.
    *
    * Quem não tem nada aqui não é escondido por isso: continua no site,
    * continua podendo se candidatar, e entra no índice no instante em que
    * preencher qualquer coisa -- o fingerprint muda e a fila o enxerga.
    */
   and (r.id is not null
        or c.headline is not null
        or coalesce(cardinality(c.skills), 0) > 0);
