# Logs e auditoria

## Três trilhas distintas

| Trilha | Finalidade | Exemplo |
| --- | --- | --- |
| log técnico | operar e diagnosticar o software | latência, erro, request id |
| trilha de auditoria | responsabilizar ações sobre dados e acesso | usuário alterou vencimento |
| auditoria empresarial | executar trabalhos, testes e achados | evidência suporta um red flag |

Misturar essas trilhas aumenta exposição e dificulta retenção. Um erro de runtime não é um achado de auditoria; um `AuditLog` não substitui evidência profissional.

## Estado atual

A fundação de dados inclui um modelo de `AuditLog` e seed fictício. A UI do protótipo não registra uma trilha completa, imutável e pronta para conformidade. Logs do runtime continuam sendo apenas telemetria técnica do protótipo.

## Evento de auditoria-alvo

Cada evento deverá registrar, conforme aplicável:

- `eventId`, timestamp UTC e request/correlation id;
- ator, tipo de ator e sessão;
- tenant, empresa e unidade do contexto;
- ação e resultado (`success`, `denied`, `failed`);
- tipo e id do recurso;
- origem da requisição e canal;
- campos alterados ou referência segura ao diff;
- justificativa para ação privilegiada;
- versão da regra ou cálculo afetado.

Não registrar segredo ou documento completo. Valores antes/depois devem ser limitados aos campos necessários, mascarados e sujeitos à mesma autorização do recurso.

## Eventos mínimos

- login, logout, falha e revogação de sessão;
- criação, alteração e remoção de membership, papel e permissão;
- troca de empresa/unidade ativa quando relevante;
- leitura/exportação de dado sensível;
- criação e alteração de pagar/receber, lançamento e conciliação;
- fechamento/reabertura de período;
- upload, download, substituição e descarte de documento/evidência;
- importação, validação, confirmação e rejeição;
- alteração de risco, achado, recomendação e plano de ação;
- geração de explicação/insight que afete decisão;
- negações e tentativas de acesso entre escopos.

## Integridade

- a aplicação escreve eventos por um serviço central, sem endpoints genéricos de edição;
- usuários comuns não alteram ou excluem eventos;
- relógios são sincronizados e timestamps usam UTC;
- armazenamento possui retenção e privilégios separados;
- eventos críticos podem usar encadeamento de hash ou exportação para repositório imutável;
- falha ao auditar ação crítica deve abortar a transação ou colocá-la em estado seguro definido.

Uma alegação de “log imutável” exige prova da configuração do ambiente; append-only em código, isoladamente, não é suficiente.

## Consulta e exportação

Consultar auditoria requer permissões específicas e escopo de tenant. Filtros por ator, ação, recurso, resultado e período devem ser paginados. Toda exportação gera outro evento de auditoria e aplica limites contra extração massiva.

## Retenção

Prazos devem ser definidos por finalidade, obrigação legal, contrato e risco. Logs técnicos tendem a precisar de menos tempo que eventos contábeis ou evidências. Legal hold suspende descarte apenas no escopo necessário.

## Auditoria empresarial

O módulo futuro seguirá:

```text
programa → procedimento/teste → amostra → evidência → achado
→ risco → recomendação → plano de ação → monitoramento
```

Um achado deve separar condição, critério, causa, efeito/risco e evidência. Severidade não será “mágica”: a matriz e seus parâmetros ficam visíveis e versionados.

## Verificação

- testes garantem evento para cada ação crítica;
- testes provam que o evento usa o ator e tenant da sessão, não do payload;
- testes de mascaramento procuram segredos e dados proibidos;
- reconciliação detecta mutações de negócio sem evento correspondente;
- restauração e exportação preservam ordem, IDs e timestamps.
