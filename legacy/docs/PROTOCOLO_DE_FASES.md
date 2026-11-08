# PROTOCOLO_DE_FASES — Clareza

## Objetivo

Eliminar o processo manual de:

Codex → relatório → ChatGPT → novo prompt → Codex.

O repositório deve carregar contexto suficiente para que cada agente saiba:

- onde o projeto está;
- o que pode fazer;
- o que não pode fazer;
- qual é o próximo gate;
- quando corrigir;
- quando avançar;
- quando parar para decisão humana.

---

# Ciclo padrão

## 1. Descobrir

Ler:

- `AGENTS.md`
- `docs/ESTADO_ATUAL.md`
- `docs/ROADMAP_MASTER.md`
- `docs/DECISOES_ARQUITETURAIS.md`
- relatório da fase anterior.

Inspecionar código atual antes de assumir que a documentação está correta.

## 2. Baseline

Executar verificações relevantes antes das mudanças.

Registrar falhas que já existiam.

## 3. Implementar

Fazer a menor alteração coerente que avance a fase.

Evitar desenvolver funcionalidade futura por conveniência.

## 4. Testar cedo

Não acumular uma grande implementação para testar somente ao final.

## 5. Revisão adversarial

Depois de funcionar, tentar quebrar.

Considerar:

- adulteração do cliente;
- concorrência;
- contexto ausente;
- reuso de conexão;
- erro no meio da transação;
- estado antigo da interface;
- dados cruzados;
- permissão reduzida;
- retry;
- duplicidade.

## 6. Corrigir automaticamente

Problemas dentro do escopo da fase devem ser corrigidos sem pedir que o usuário transporte a falha para outro chat.

## 7. Regressão completa

Executar o gate e as suítes relevantes.

## 8. Documentar

Gerar relatório objetivo com evidências.

Atualizar estado atual e roadmap.

## 9. Decidir

### Gate aprovado + `AUTO_ADVANCE: true`

Marcar fase como `APROVADA`, próxima como `EM EXECUÇÃO` e continuar.

### Gate aprovado + `AUTO_ADVANCE: false`

Parar e apresentar a decisão humana necessária.

### Gate falhou

Marcar como `EM CORREÇÃO`, corrigir e retestar.

### Bloqueio externo

Marcar como `BLOQUEADA`, documentar:

- causa;
- tentativas;
- alternativa segura;
- impacto.

Não fingir conclusão.

---

# Formato do relatório por fase

```markdown
# Fase N — Nome

## Objetivo
## Estado inicial
## Alterações implementadas
## Arquivos alterados
## Banco/migrations
## Testes executados
## Evidências
## Revisão adversarial
## Problemas encontrados
## Correções realizadas
## Segurança
## Regressões
## Pendências
## Gate
## Veredito
```

---

# Critério de evidência

São evidências fortes:

- teste automatizado reproduzível;
- saída de build/lint/typecheck;
- teste de integração real;
- consulta controlada ao banco;
- request/response verificável;
- diff de schema;
- constraint/policy testada negativamente.

São evidências fracas e insuficientes sozinhas:

- “o código parece correto”;
- “a tela abriu”;
- “não encontrei problema”;
- comentário no código;
- mock;
- screenshot sem validação da origem dos dados.

---

# Regra para relatórios longos

O relatório deve ser completo no arquivo, mas a resposta final do Codex pode ser executiva:

- fase;
- veredito;
- testes;
- mudanças críticas;
- riscos;
- próxima ação.

O usuário não precisa copiar o relatório para outra IA para obter o próximo prompt.

---

# Auditoria estratégica externa

É recomendável revisão estratégica humana/ChatGPT em marcos maiores, não a cada pequena fase.

Marcos sugeridos:

- antes da Fase 4 (contabilidade);
- antes da Fase 6 (fiscal);
- antes da Fase 8 (IA);
- antes da Fase 9 (produção);
- quando houver mudança arquitetural relevante;
- quando surgir falha de segurança significativa.
