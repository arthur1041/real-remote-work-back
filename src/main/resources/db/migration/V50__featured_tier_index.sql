-- ============================================================================
-- Quem publicou a vaga, perguntado a partir da vaga.
--
-- **Para que serve.** As seções "Featured roles" passam a ordenar por camada:
-- primeiro as vagas de quem paga assinatura, depois as de quem publicou de
-- graça, depois as escolhidas do corpus. A pergunta "esta vaga é de um
-- assinante?" só tem uma resposta certa: a conta que a publicou, que está em
-- `employer_postings.job_id`.
--
-- **Por que não dá para perguntar pela empresa.** O caminho antigo era
-- `jobs -> companies -> employers -> employer_subscriptions`, e ele responde
-- outra coisa: "a empresa desta vaga é a empresa de alguma conta?". Isso valia
-- enquanto quem publicava era sempre a própria contratante. A V49 acabou com
-- isso: uma vaga que uma agência publicou para um cliente é arquivada sob o
-- *cliente*, nenhuma conta aponta para aquela `companies`, e o left join não
-- acha ninguém -- de modo que a agência pagante cairia na camada grátis
-- exatamente nas vagas pelas quais ela paga. O digest semanal tem hoje o mesmo
-- caminho e o mesmo defeito, registrado em `docs/TODOs.md`.
--
-- **Por que um índice.** `employer_postings` só tinha índices por
-- `employer_id` e pela fila de revisão; ninguém nunca havia entrado nela pela
-- vaga. Agora a home e o índice de vagas com restrição entram por aí a cada
-- revalidação, e a tabela cresce com cada vaga enviada, não com cada vaga
-- aprovada. Parcial porque só a linha aprovada tem `job_id`: as pendentes e as
-- recusadas são a maioria e não interessam a esta consulta.
-- ============================================================================

create index employer_postings_by_job
    on employer_postings (job_id)
    where job_id is not null;
