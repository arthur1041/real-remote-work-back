-- A frase de garantia passa a ter um dono só.
--
-- A tela do employer escreve "No reading for this one." + o motivo guardado +
-- "Nothing about this application has been assumed." O texto que o leitor
-- gravava já trazia a segunda frase inteira, então o employer lia duas vezes
-- a mesma garantia e duas vezes a mesma causa:
--
--   "No reading for this one. The reading could not be completed. Nothing
--    about this application has been assumed. Nothing about this application
--    has been assumed."
--
-- Visto de ponta a ponta em 15 Nov 2026, numa leitura que falhou com 429 do
-- Vertex. O `ApplicationScreener` agora grava só a causa; isto conserta as
-- linhas que já existem, porque uma linha que esgotou as três tentativas
-- guarda esse texto para sempre.
update application_screenings
   set failure = 'The model did not answer.'
 where failure = 'The reading could not be completed. Nothing about this application has been assumed.';

-- E a mensagem do parser, que nunca deveria ter ido para a tela de ninguém:
-- era um erro do Jackson com offset de byte, e podia carregar um pedaço do
-- que o modelo devolveu.
update application_screenings
   set failure = 'The answer came back in a shape this could not read.'
 where failure like 'The answer could not be read:%';
