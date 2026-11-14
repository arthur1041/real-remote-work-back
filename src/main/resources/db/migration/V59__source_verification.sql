-- A vaga publicada aqui é a única do quadro sem sinal de vida.
--
-- `JobRepository.closeStale` fecha a vaga que sumiu do quadro ATS da empresa;
-- `closeExpired` aposenta a de feed na data que o publicante declarou. As duas
-- excluem `ats_type = 'DIRECT'` de propósito, porque o `last_seen_at` de uma
-- vaga direta fica congelado na submissão e a primeira coleta depois da
-- aprovação a fecharia. Resultado: vaga que o empregador digitou aqui fica no ar
-- até ele mesmo fechar.
--
-- Medido em 14 Nov 2026: **380 das 384 vagas abertas de empregador não têm data
-- de fechamento.** Quatro têm. E 327 delas são de uma conta só, publicadas em 12
-- e 13 de novembro, apontando para páginas de vaga de terceiros. Daqui a três
-- meses ninguém vai saber quais ainda existem.
--
-- O que estas colunas sustentam é perguntar à fonte, vaga por vaga. A idade
-- decide **quando** perguntar, nunca se a vaga fica: quem responde é a página de
-- candidatura do próprio empregador, e "não sei" é resposta de primeira classe
-- que não fecha nada. Ver `ApplySource` para os marcadores e para as duas
-- armadilhas que quase passaram.

-- ---------------------------------------------------------------- a pausa

-- **SOURCE_CLOSED entra na lista de motivos.**
--
-- Pausa, não exclusão, e por três razões que já valiam para PLAN_LAPSE: a
-- submissão continua no painel do empregador, o slot do plano grátis volta para
-- ele sozinho (`postingAllowance` conta APPROVED com `closed_at is null`), e
-- `activatePosting` já deixa ele desfazer com um clique. Um fechamento
-- automático que o dono não possa reverter seria uma máquina decidindo sozinha
-- sobre o anúncio de quem paga.
--
-- Diferente de PLAN_LAPSE em uma coisa, e ela importa: o retorno da assinatura
-- **não** desfaz este motivo. Voltar a pagar não torna a vaga viva de novo.
alter table employer_postings drop constraint employer_postings_pause_reason_valid;

alter table employer_postings add constraint employer_postings_pause_reason_valid
    check (
        (paused_at is null and pause_reason is null)
        or (paused_at is not null
            and pause_reason in ('PLAN_LAPSE', 'EMPLOYER', 'SOURCE_CLOSED'))
    );

-- ------------------------------------------------------------ a verificação

-- Quando a fonte foi perguntada pela última vez. Null = nunca.
alter table employer_postings add column source_checked_at timestamptz;

-- O que ela respondeu. UNKNOWN é resposta, e é a segura.
alter table employer_postings add column source_state text
    check (source_state is null or source_state in ('OPEN', 'CLOSED', 'UNKNOWN'));

-- **Quantos "não sei" seguidos.**
--
-- Um timeout é ruído; cinco seguidos no mesmo link são um formato de página que
-- mudou, um bloqueio de bot novo, ou um host que morreu. Nenhum dos três fecha
-- vaga -- os três merecem um humano, e sem este contador a diferença entre
-- "ninguém perguntou" e "pergunta-se há um mês sem resposta" não existe.
alter table employer_postings add column source_unknown_streak smallint not null default 0;

-- **O empregador disse que a vaga está aberta, contra o que a fonte diz.**
--
-- Gravado quando ele reativa uma vaga que foi pausada por SOURCE_CLOSED. A
-- partir daí a verificação continua perguntando e continua registrando, e nunca
-- mais pausa esta vaga sozinha: vira item para uma pessoa olhar. A palavra do
-- dono vale mais que a leitura de uma página de terceiro, e um laço em que a
-- máquina repausa o que o humano reativou é a pior forma de descobrir isso.
alter table employer_postings add column source_override_at timestamptz;

-- O aviso de pausa que saiu para o empregador, no mesmo formato de
-- `decision_notified_at`: null = ainda não foi.
alter table employer_postings add column pause_notified_at timestamptz;

-- A varredura pergunta sempre a mesma coisa: aprovadas, não pausadas, ordenadas
-- por quem foi perguntado há mais tempo. Parcial porque a parte interessante é
-- minúscula -- algumas centenas de submissões contra 17 mil vagas no quadro.
create index employer_postings_source_due
    on employer_postings (source_checked_at nulls first)
 where status = 'APPROVED' and paused_at is null;

comment on column employer_postings.source_state is
    'O que a página de candidatura respondeu na última verificação: OPEN, CLOSED ou UNKNOWN.';
