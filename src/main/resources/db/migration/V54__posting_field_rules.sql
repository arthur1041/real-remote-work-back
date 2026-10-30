-- Obrigatoriedade e condição para os campos do catálogo.
--
-- A V53 disse que "tipo de dado, obrigatoriedade e opções são o tipo de coisa
-- que transforma isto num construtor de formulários, e um construtor de
-- formulários é outro produto". Era verdade naquele momento e deixou de ser:
-- a triagem que o plano Growth vende avalia respostas, e resposta tipada é
-- avaliável sem modelo nenhum enquanto um parágrafo não é. O construtor
-- passou a ser o produto, e não um desvio dele.
--
-- As perguntas escritas pelo empregador já guardam tipo, opções,
-- obrigatoriedade e condição dentro do próprio `custom_questions`, que é
-- jsonb e não precisou mudar de forma. O que não cabia lá é o catálogo:
-- `application_fields` é um `text[]` de códigos, e um código não tem onde
-- pendurar "este é obrigatório" nem "só pergunte isto se a anterior foi
-- Sim". Daí esta coluna, indexada pelo mesmo código do catálogo.
--
-- Formato: {"SKILLS": {"required": true},
--           "COVER_NOTE": {"showIf": {"ref": "<id|CODIGO>", "is": ["Yes"]}}}
--
-- Um código ausente é o padrão de sempre: perguntado se o empregador marcou,
-- opcional, sem condição. Por isso o default é um objeto vazio e nenhuma vaga
-- existente muda de comportamento.
alter table employer_postings
    add column field_rules jsonb not null default '{}'::jsonb;

-- A mesma coisa na vaga publicada. O formulário do candidato lê de `jobs`, e
-- `approvePosting` copia: a vaga no ar é um retrato aprovado da submissão, e
-- não um ponteiro para ela.
alter table jobs
    add column field_rules jsonb not null default '{}'::jsonb;

comment on column employer_postings.field_rules is
    'Regras por código do catálogo: obrigatoriedade e condição de exibição. '
    'Código ausente significa opcional e sem condição.';
comment on column jobs.field_rules is
    'Cópia aprovada de employer_postings.field_rules, feita na aprovação.';
