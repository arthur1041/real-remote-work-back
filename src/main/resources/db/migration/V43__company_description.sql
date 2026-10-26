-- ============================================================================
-- A descrição que o próprio empregador escreve sobre a empresa.
--
-- Parte do passo 8 de `docs/plano-starter.md`: a página da empresa
-- enriquecida.
--
-- **Por que isto vale alguma coisa.** 425 das empresas deste board têm
-- exatamente uma vaga rastreada, e a página delas é um cartão, um título e
-- mais nada -- e são justamente essas páginas que o Google já ranqueia em
-- segundo e terceiro lugar para buscas como "icuc social careers". O texto
-- gerado que preenche hoje ("What X's hiring looks like") existe porque a
-- alternativa era página vazia. Um parágrafo escrito por quem contrata é
-- melhor do que qualquer coisa que se consiga derivar de contagens.
--
-- **Na `companies` e não na `employers`.** É a página da empresa que lê, e a
-- linha DIRECT de um empregador é sempre criada por `resolveDirectCompany`
-- com a chave `employer-<id>` -- nunca casa com uma empresa raspada. Então
-- não existe o caso de um empregador escrever na página de outra pessoa.
--
-- **Fica quando a assinatura acaba.** Escrever é do plano pago; o texto já
-- escrito continua aparecendo. Derrubar o parágrafo de uma empresa porque um
-- cartão venceu piora a página para o candidato, que não tem nada a ver com
-- isso, e este board resolve esse tipo de empate a favor do candidato.
-- ============================================================================

alter table companies add column description text;

comment on column companies.description is
    'Escrita pelo empregador. Nula em toda empresa raspada, que não tem quem escreva.';
