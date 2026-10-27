-- ============================================================================
-- O formulário de vaga enquanto está sendo preenchido.
--
-- **Por que não é um `status = 'DRAFT'` em `employer_postings`.** Aquela
-- tabela tem `not null` em `title`, `description_source` e `geo_scope`. Um
-- rascunho é, por definição, incompleto: o primeiro salvamento acontece antes
-- de qualquer campo existir. Encaixá-lo ali significaria derrubar essas três
-- restrições para **toda** vaga de verdade, a fim de acomodar linhas que não
-- são vagas -- e o papel daquelas restrições é justamente garantir que nada
-- chegue à revisão pela metade.
--
-- O segundo motivo é mais caro que o primeiro. Hoje nove consultas leem
-- `employer_postings` sem olhar o status, ou olhando só os que conhecem:
-- a fila da revisão, o painel do admin, a listagem do empregador, a contagem
-- do teto, e os quatro índices parciais acima de `status = 'PENDING'`. Um
-- status novo obrigaria cada uma delas a aprender a ignorá-lo, e a que
-- esquecesse colocaria um formulário vazio na fila de revisão de uma pessoa,
-- ou no board.
--
-- **Um rascunho por empregador, e a chave primária diz isso.** O fluxo que
-- ele atende é "abri o formulário, preenchi metade, fechei o navegador" --
-- e, quando se volta, não existe ambiguidade sobre qual retomar. Duas abas
-- do mesmo empregador escrevendo ao mesmo tempo terminam com a última a
-- gravar, que é o comportamento de qualquer rascunho salvo sozinho e o que
-- as duas abas já faziam entre si antes disto existir.
--
-- **`jsonb` e não colunas.** Isto é o estado de um formulário, não uma
-- entidade: nada aqui é consultado, agregado ou juntado com outra coisa, e o
-- único leitor é o próprio formulário, que grava o que tem e lê de volta o
-- que gravou. Uma pergunta nova no formulário não deve custar uma migração.
-- O dia em que algo aqui precisar ser consultado é o dia em que deixou de ser
-- rascunho e virou vaga, e aí já existe tabela para isso.
--
-- `on delete cascade`: um empregador apagado não deixa para trás o que estava
-- digitando.
-- ============================================================================

create table employer_posting_drafts (
    employer_id bigint primary key references employers (id) on delete cascade,
    payload     jsonb       not null default '{}'::jsonb,
    updated_at  timestamptz not null default now()
);

comment on table employer_posting_drafts is
    'O formulário de nova vaga enquanto é preenchido. Um por empregador; '
    'apagado quando a vaga é enviada para revisão.';

comment on column employer_posting_drafts.payload is
    'Os campos do formulário como o navegador os mandou. Lido só pelo próprio '
    'formulário, para repopulá-lo; nunca consultado por conteúdo.';
