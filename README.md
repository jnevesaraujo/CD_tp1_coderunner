# CodeRunner

Sistema distribuído para submissão e execução isolada de programas Python em
containers Docker.

O projeto é constituído por:

- `CodeRunnerManagerServer`: gere os RunServers disponíveis e fornece o acesso
  ao Redis;
- `CodeRunnerRunServer`: recebe pedidos, executa os programas em containers e
  disponibiliza os resultados;
- `CodeRunnerClient`: submete programas e consulta os resultados;
- `CodeRunnerDemo`: executa vários clientes em simultâneo para demonstrar o
  funcionamento do sistema;
- `CodeRunnerContract`: contém os contratos gRPC utilizados pelos componentes.

Os pedidos são identificados por um UUID e os seus estados são guardados no
Redis. Cada programa é executado num container sem rede, com limite de memória
e tempo de execução.

## Compilação

```text
./gradlew build
```

No Windows:

```text
gradlew.bat build
```

## Programas de demonstração

Os programas usados pelo Demo encontram-se em `CodeRunnerDemo/demo-programs`.
Incluem exemplos de execução concluída, erro, timeout e limite de memória.
