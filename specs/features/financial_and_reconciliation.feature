# language: pt
Funcionalidade: Isolamento Multi-Tenant, Conciliação Bancária e Invariantes Financeiras

  Contexto:
    Dado que o banco PostgreSQL 16 possui as migrations Flyway V1 a V8 aplicadas
    E que toda operação roda sob a role restrita "clarity_app" sem BYPASSRLS

  Cenário: Rejeição de baixa concorrente acima do saldo em aberto
    Dado um título a pagar "OPEN" com valor principal de "1000.0000" BRL na Empresa Alfa
    Quando duas requisições concorrentes com chaves de idempotência distintas tentam baixar "600.0000" BRL
    Então apenas uma baixa é persistida com status 201 e saldo restante "400.0000"
    E a segunda requisição falha fechada com status 409 e código "FINANCIAL_STATE_CONFLICT"

  Cenário: Conciliação bancária 1:N equilibrada e bloqueio de estorno financeiro direto
    Dado uma transação bancária importada de "-500.0000" BRL na conta corrente principal
    E dois pagamentos registrados de "300.0000" BRL e "200.0000" BRL na mesma conta corrente
    Quando o operador cria uma conciliação vinculando a transação bancária aos dois pagamentos
    Então o grupo de conciliação é criado com status 201 e reserva exclusivamente os 3 participantes
    E qualquer tentativa de estornar um dos pagamentos conciliados falha com status 409
    Quando o operador desfaz a conciliação informando motivo válido
    Então um evento append-only de reversão é gravado e os pagamentos voltam a permitir estorno ou nova conciliação
