-- ============================================================================
-- Quem está publicando: a empresa que contrata, ou um terceiro.
--
-- **Isto conserta uma afirmação falsa.** O selo nas vagas diz "Posted by the
-- employer" e é derivado só de `ats_type = 'DIRECT'`, ou seja, de qualquer
-- coisa submetida pelo formulário. Um recrutador terceirizado publicava e a
-- vaga ganhava um selo afirmando que a própria empresa a publicou -- numa
-- superfície pública, na única afirmação que este board faz sobre procedência.
--
-- **Nulo nas contas que já existem, e é de propósito.** Não há de onde
-- inferir, e inventar a resposta seria exatamente o problema que esta coluna
-- resolve. Vaga de conta sem resposta não recebe selo nenhum, que é a saída
-- honesta: o selo afirma algo, e não afirmar é melhor do que afirmar sem
-- saber.
--
-- **Declarado, não verificado** -- como a elegibilidade. O board trata
-- declaração como declaração e confere contra o texto do anúncio; é a mesma
-- postura, aplicada à mesma pessoa, no mesmo formulário.
-- ============================================================================

alter table employers
    add column posts_as text check (posts_as in ('EMPLOYER', 'RECRUITER'));

comment on column employers.posts_as is
    'EMPLOYER = contrata para si. RECRUITER = publica para terceiros. Nulo nas contas anteriores a 27 Out 2026.';

-- O selo é lido em toda listagem de vagas, que passa por `companies` e agora
-- precisa chegar ao empregador dono daquela empresa DIRECT. Sem este índice a
-- junção é um sequential scan por consulta -- barato com trinta empregadores e
-- não com trinta mil.
--
-- **Único, e isso importa mais que a velocidade.** A junção é por
-- `company_id`; se dois empregadores apontassem para a mesma empresa, toda
-- vaga dela apareceria duplicada em toda listagem do site, silenciosamente.
-- Hoje é 1:1 porque `resolveDirectCompany` cria uma linha por empregador com
-- a chave `employer-<id>`, mas nada impedia o contrário. Agora impede.
create unique index employers_company on employers (company_id) where company_id is not null;
