-- ============================================================================
-- A data em que a vaga do empregador deixa de ser exibida.
--
-- **Não é mecanismo novo.** `jobs.expires_at` já existe e já é respeitado:
-- `JobRepository.closeExpired()` fecha toda vaga cuja data passou, e a partir
-- daí as dezenove consultas que filtram `closed_at is null` a escondem sem
-- saber que existe expiração. Em produção isso roda junto com a ingestão e
-- deixa cinco vencidas no ar entre uma varredura e outra; num banco parado
-- elas se acumulam.
--
-- O que faltava era o empregador poder preencher essa data. Até aqui só as
-- vagas de feed tinham expiração -- 1.149 das 16.177 abertas --, porque o
-- WeWorkRemotely a publica. Quem posta aqui não tinha como dizer "isto vale
-- até tal dia", e a vaga ficava no ar até alguém reparar.
--
-- **Opcional de propósito, e é por isso que os termos ganharam um parágrafo.**
-- A maioria não sabe a data no dia em que publica. Deixar em branco continua
-- sendo o caminho normal; o que muda é que fica escrito, no formulário e nos
-- termos, que uma vaga sem data pode ser retirada quando se constatar que não
-- vale mais. Sem isso, "sem data" significaria "para sempre", que não é o que
-- nenhum dos dois lados quer.
--
-- `date` e não `timestamptz`: ninguém escolhe o horário em que uma vaga
-- expira, e guardar uma hora que o formulário não pergunta é inventar
-- precisão. A varredura compara com `now()`, então a vaga sai no primeiro
-- passo depois da meia-noite daquele dia.
-- ============================================================================

alter table employer_postings add column expires_at date;

comment on column employer_postings.expires_at is
    'Opcional. Copiada para jobs.expires_at na aprovação; de lá, closeExpired() a retira.';
