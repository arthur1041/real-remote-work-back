-- Perguntas que o empregador escreve, além do catálogo fixo.
--
-- O catálogo de `application_fields` é fixo de propósito: tudo que vem de lá
-- é entendido pelo formulário que pergunta, pela caixa que exibe e por
-- qualquer busca futura sobre candidaturas. Uma pergunta livre não é nada
-- disso -- é texto de uma pessoa para outra -- e por isso mora em coluna
-- própria, em vez de virar código inventado dentro da lista fixa.
--
-- Formato: [{"q": "texto da pergunta", "long": false}]. `long` escolhe entre
-- uma linha e um parágrafo, que é a única variação que vale neste primeiro
-- momento; tipo de dado, obrigatoriedade e opções são o tipo de coisa que
-- transforma isto num construtor de formulários, e um construtor de
-- formulários é outro produto.
alter table employer_postings
    add column custom_questions jsonb not null default '[]'::jsonb;

-- A mesma coisa na vaga publicada. O formulário do candidato lê de `jobs`, e
-- `approvePosting` copia: a vaga no ar é um retrato aprovado da submissão, e
-- não um ponteiro para ela.
alter table jobs
    add column custom_questions jsonb not null default '[]'::jsonb;

-- A resposta guarda a pergunta junto.
--
-- Não é redundância: o empregador pode editar a vaga depois, e uma resposta
-- que só guardasse o índice da pergunta passaria a responder outra coisa, ou
-- coisa nenhuma. Formato: [{"q": "...", "a": "..."}], na ordem em que foram
-- perguntadas.
alter table job_applications
    add column custom_answers jsonb not null default '[]'::jsonb;

comment on column employer_postings.custom_questions is
    'Perguntas escritas pelo empregador, no máximo três. Texto puro, revisado '
    'junto com a vaga: é conteúdo que vai ser mostrado a candidatos.';
comment on column jobs.custom_questions is
    'Cópia aprovada de employer_postings.custom_questions, feita na aprovação.';
comment on column job_applications.custom_answers is
    'Pares pergunta/resposta, com a pergunta guardada junto para sobreviver a '
    'uma edição posterior da vaga.';
