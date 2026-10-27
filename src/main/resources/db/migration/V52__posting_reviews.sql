-- ============================================================================
-- O que foi decidido sobre qual versão de qual vaga, e por quem.
--
-- **O problema que isto resolve.** A V42 guardava o veredito do modelo em três
-- colunas da própria `employer_postings`. Funciona enquanto a vaga não muda --
-- e ela muda: `updatePosting` reescreve título, descrição e elegibilidade na
-- mesma linha e zera as três colunas. Depois de uma edição não existia mais
-- nenhum registro do que o modelo tinha lido nem do que ele tinha concluído.
-- Uma aprovação de ontem passava a parecer uma aprovação do texto de hoje.
--
-- **`content_version` é o que torna uma decisão citável.** Começa em 1 e sobe
-- a cada reenvio. Uma decisão guarda a versão que foi julgada, então "aprovada"
-- quer dizer "aprovada a versão 2", e a versão 3 não herda nada.
--
-- Também fecha a corrida: o pipeline lê a versão 2, o empregador reenvia
-- enquanto o modelo pensa, e o veredito chega para uma vaga que já é a versão
-- 3. O endpoint compara e descarta -- a vaga volta à varredura em vez de ser
-- publicada com uma análise que não é dela.
--
-- **O snapshot é jsonb e é o texto julgado, não um ponteiro para ele.** Um
-- ponteiro para a linha atual responde "o que está lá agora", que é
-- exatamente a pergunta que já se podia fazer. O que faltava é "o que estava
-- lá quando se decidiu".
--
-- **Uma decisão por linha, modelo e pessoa na mesma tabela.** Elas respondem a
-- mesma pergunta e são lidas juntas na mesma tela; separá-las em duas tabelas
-- obrigaria toda leitura a uni-las de novo. `decided_by` diz qual é qual.
--
-- **O modelo nunca recusa, e o banco é onde isso para de ser promessa.** O
-- check abaixo deixa MODEL gravar só APPROVED e REFERRED. Recusar, remover do
-- ar e restaurar são decisões de ADMIN, pelo motivo da decisão 4 do
-- `docs/plano-starter.md`: fechar a porta de um cliente pagante é coisa que
-- uma pessoa faz. O que o modelo *pode* é propor -- a proposta fica em
-- `findings`, e aparece na fila humana já escrita.
-- ============================================================================

alter table employer_postings
    -- Sobe a cada reenvio. O default 1 vale para tudo que já existe: ninguém
    -- editou nada antes desta coluna, então tudo que está lá é a versão 1.
    add column content_version integer not null default 1;

alter table employer_postings
    drop constraint employer_postings_status_check;

alter table employer_postings
    add constraint employer_postings_status_check
    -- REMOVED é novo: uma vaga que estava no ar e foi tirada por um
    -- administrador. Não é WITHDRAWN, que é o empregador desistindo da
    -- própria vaga, e não é REJECTED, que nunca chegou a ser publicada. A
    -- diferença importa na tela do empregador e no teto de vagas ativas, que
    -- conta APPROVED.
    check (status in ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'REMOVED'));

create table posting_reviews (
    id              bigserial   primary key,
    posting_id      bigint      not null references employer_postings (id) on delete cascade,

    -- A versão julgada. Não é "a versão atual": é a que estava na mesa.
    content_version integer     not null,

    -- O que foi julgado, como chegou. Título, texto, elegibilidade, destino da
    -- candidatura, pagamento. Imutável por convenção: nada reescreve uma linha
    -- desta tabela.
    snapshot        jsonb       not null,

    decision        text        not null
                    check (decision in ('APPROVED', 'REFERRED', 'REJECTED', 'REMOVED', 'RESTORED')),

    decided_by      text        not null check (decided_by in ('MODEL', 'ADMIN')),

    -- O modelo só aprova ou refere. Ver o cabeçalho.
    constraint posting_reviews_model_never_refuses
        check (decided_by = 'ADMIN' or decision in ('APPROVED', 'REFERRED')),

    -- Em palavras: a nota do modelo, ou o que o administrador escreveu ao
    -- sobrescrever. Nulo numa aprovação sem ressalva.
    reason          text,

    -- De onde veio a decisão do modelo, para quando a resposta de hoje não for
    -- a de amanhã: qual modelo, e qual versão do que lhe foi perguntado.
    model           text,
    policy_version  text,

    -- Códigos, severidade, citação, avaliação geográfica e a proposta do
    -- modelo. jsonb porque isto vai mudar mais que o resto da tabela e uma
    -- pergunta nova no prompt não deve custar uma migração.
    findings        jsonb,

    -- Qual decisão esta aqui sobrescreve. É o que permite dizer "o modelo
    -- aprovou e uma pessoa tirou do ar" sem ter que inferir pela ordem.
    overrides_id    bigint      references posting_reviews (id),

    created_at      timestamptz not null default now()
);

-- Como a varredura pergunta: esta vaga já foi lida pelo modelo nesta versão?
create unique index posting_reviews_one_model_read_per_version
    on posting_reviews (posting_id, content_version)
    where decided_by = 'MODEL';

-- Como o painel lê: tudo, do mais recente para o mais antigo.
create index posting_reviews_recent on posting_reviews (created_at desc);

-- E como a tela de uma vaga lê: a história dela, em ordem.
create index posting_reviews_by_posting on posting_reviews (posting_id, created_at);

comment on table posting_reviews is
    'Uma linha por decisão de moderação, do modelo ou de uma pessoa, presa à '
    'versão do conteúdo que foi julgada. Nada aqui é reescrito.';

-- As três colunas da V42 saem: o que elas guardavam agora tem lugar próprio,
-- com a versão junto. Duas fontes para a mesma pergunta é a fonte que diverge.
--
-- A V42 não é emendada porque ela já rodou nas máquinas de teste; desfazer
-- aqui é o caminho que não exige reparar checksum de ninguém.
drop index if exists employer_postings_awaiting_auto_review;

alter table employer_postings
    drop column if exists auto_reviewed_at,
    drop column if exists auto_review_verdict,
    drop column if exists auto_review_note;

-- A fila do modelo passa a ser "PENDING que ainda não tem leitura nesta
-- versão", e o índice volta a ser só o status: o resto da pergunta é um
-- not exists contra a tabela acima, que tem o índice único para ele.
create index employer_postings_pending_by_age
    on employer_postings (created_at)
    where status = 'PENDING';
