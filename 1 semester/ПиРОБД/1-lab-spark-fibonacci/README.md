spark-fibonacci

```bash
./gradlew clean build
```

```bash
docker compose up -d
```

```bash
docker compose exec spark-master /opt/spark/bin/spark-submit \
  --class com.volhv.FibonacciApp \
  --master spark://spark-master:7077 \
  --deploy-mode client \
  /opt/spark-apps/fibonacci-app.jar 10
```