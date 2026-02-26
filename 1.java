@Override
public InputStream streamLogs(PodLogFilter filter) throws IOException {
    PipedInputStream pipedIn = new PipedInputStream(1024 * 1024); // buffer 1MB
    PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);

    executorService.submit(() -> {
        try {
            PodLogs podLogs = new PodLogs(
                kubernetesConnectionFactory.getCoreApi(filter.provider()).getApiClient()
            );

            Instant now = Instant.now();
            Instant windowStart = now.minus(7, ChronoUnit.DAYS);

            // Monta todas as janelas
            List<Instant> windows = new ArrayList<>();
            while (windowStart.isBefore(now)) {
                windows.add(windowStart);
                windowStart = windowStart.plus(1, ChronoUnit.HOURS);
            }

            // Processa em lotes de 10 janelas em paralelo
            int batchSize = 10;
            for (int i = 0; i < windows.size(); i += batchSize) {
                List<Instant> batch = windows.subList(i, Math.min(i + batchSize, windows.size()));

                // Dispara o lote em paralelo e coleta os resultados em ordem
                List<CompletableFuture<byte[]>> futures = batch.stream()
                    .map(wStart -> CompletableFuture.supplyAsync(() -> {
                        Instant wEnd = wStart.plus(1, ChronoUnit.HOURS);
                        int sinceSeconds = (int) ChronoUnit.SECONDS.between(wStart, Instant.now());
                        try (InputStream stream = podLogs.streamNamespacedPodLog(
                                filter.namespace(), filter.name(), filter.container(),
                                sinceSeconds, null, true)) {

                            // Filtra apenas linhas da janela
                            return Arrays.stream(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("
"))
                                .filter(line -> isWithinWindow(line, wStart, wEnd))
                                .collect(Collectors.joining("
"))
                                .getBytes(StandardCharsets.UTF_8);

                        } catch (Exception e) {
                            log.error("Erro na janela {}", wStart, e);
                            return new byte[0];
                        }
                    }, executorService))
                    .toList();

                // Escreve no pipe em ordem cronológica assim que o lote termina
                for (CompletableFuture<byte[]> future : futures) {
                    pipedOut.write(future.join());
                    pipedOut.write('
');
                }
            }

        } catch (Exception e) {
            log.error("Erro ao coletar logs do pod", e);
        } finally {
            try { pipedOut.close(); } catch (IOException ignored) {}
        }
    });

    return pipedIn;
}
