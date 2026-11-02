# ZyvoxMr1

> O futuro melhor MR.

Repositório em estágio inicial. Este README define a estrutura básica de documentação
e os próximos passos do projeto.

## Status

| Item | Estado |
| --- | --- |
| Escopo do produto | ⬜ A definir |
| Stack / runtime | ⬜ A definir |
| CI | ⬜ A definir |
| Testes | ⬜ A definir |

## Estrutura de pastas

Ainda não há código-fonte versionado. Quando a stack for definida, a sugestão é:

```
.
├── docs/       # decisões de arquitetura (ADRs), guias e referências
├── src/        # código-fonte
├── tests/      # testes automatizados
└── README.md
```

## Primeiros passos

1. **Definir o escopo** — qual problema o "MR" resolve e para quem.
2. **Escolher a stack** — linguagem, framework e runtime.
3. **Adicionar `.editorconfig`** — consistência de formatação entre contribuidores.
4. **Configurar CI** — lint + testes em cada pull request.
5. **Primeiro módulo** — esqueleto executável com um teste cobrindo o caminho feliz.

## Convenções

- **Branches**: `arena/<id>` ou `feat/<descricao-curta>` e `fix/<descricao-curta>`, a partir de `main`.
- **Commits**: imperativos e curtos (`add`, `fix`, `remove`), no padrão
  [Conventional Commits](https://www.conventionalcommits.org/) quando possível.
- **Pull requests**: descrever o que mudou, por que mudou e como foi validado.
- **Idioma**: documentação em português; identificadores de código em inglês.

## Licença

Ainda não definida.
