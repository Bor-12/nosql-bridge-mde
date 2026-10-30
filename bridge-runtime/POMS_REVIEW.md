# Revisión de los POM de NoSQL Bridge

El padre bridge-runtime agrega los seis módulos y centraliza Java 21, Spring Boot, AWS SDK y JaCoCo. El groupId común es dev.borja.nosqlbridge.

| Módulo | Responsabilidad | Dependencias directas |
| --- | --- | --- |
| bridge-common | Modelos, planes, contratos, registro y serialización compartidos. | tools.jackson.core:jackson-databind (compile); org.springframework.boot:spring-boot-autoconfigure (compile); org.springframework:spring-context (compile) |
| bridge-mongodb | Lectura y particionado de MongoDB. | dev.borja.nosqlbridge:bridge-common (compile); org.springframework.boot:spring-boot-starter-data-mongodb (compile); org.springframework.boot:spring-boot-starter-validation (compile) |
| bridge-dynamodb | Escritura y conversiones de DynamoDB. | dev.borja.nosqlbridge:bridge-common (compile); software.amazon.awssdk:dynamodb (compile); org.springframework.boot:spring-boot-starter-validation (compile); org.springframework.boot:spring-boot-autoconfigure (compile); org.springframework:spring-context (compile) |
| bridge-sqs | Adaptador de mensajería Amazon SQS. | dev.borja.nosqlbridge:bridge-common (compile); software.amazon.awssdk:sqs (compile); org.springframework.boot:spring-boot-autoconfigure (compile); org.springframework:spring-context (compile) |
| bridge-worker | Motor genérico, mapping, consumo, CLI local y worker autónomo. | dev.borja.nosqlbridge:bridge-common (compile); dev.borja.nosqlbridge:bridge-mongodb (runtime); dev.borja.nosqlbridge:bridge-dynamodb (runtime); dev.borja.nosqlbridge:bridge-sqs (runtime); org.springframework.boot:spring-boot-starter-actuator (compile) |
| bridge-orchestrator | Particionado de migraciones existente; punto de entrada distribuido pendiente. | dev.borja.nosqlbridge:bridge-common (compile); dev.borja.nosqlbridge:bridge-mongodb (runtime); dev.borja.nosqlbridge:bridge-sqs (runtime) |

Todos los módulos heredan spring-boot-starter-test únicamente en scope test. Los adaptadores dependen de bridge-common. El worker incorpora MongoDB, DynamoDB y SQS en runtime; el orquestador incorpora MongoDB y SQS en runtime, sin depender del worker ni de DynamoDB.

Solo bridge-worker se empaqueta como ejecutable Spring Boot. Los demás generan JARs normales; el orquestador conserva ese formato hasta implementar su arranque real. Este documento es una copia para revisión y no interviene en Maven.

## pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>
    <groupId>dev.borja.nosqlbridge</groupId>
    <artifactId>bridge-runtime</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>
    <properties>
        <java.version>21</java.version>
        <aws-sdk.version>2.55.14</aws-sdk.version>
    </properties>
    <modules>
        <module>bridge-common</module>
        <module>bridge-mongodb</module>
        <module>bridge-dynamodb</module>
        <module>bridge-sqs</module>
        <module>bridge-worker</module>
        <module>bridge-orchestrator</module>
    </modules>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.borja.nosqlbridge</groupId>
                <artifactId>bridge-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.borja.nosqlbridge</groupId>
                <artifactId>bridge-mongodb</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.borja.nosqlbridge</groupId>
                <artifactId>bridge-dynamodb</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>dev.borja.nosqlbridge</groupId>
                <artifactId>bridge-sqs</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>dynamodb</artifactId>
                <version>${aws-sdk.version}</version>
            </dependency>
            <dependency>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>sqs</artifactId>
                <version>${aws-sdk.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.14</version>
                <executions>
                    <execution>
                        <goals><goal>prepare-agent</goal></goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals><goal>report</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

## bridge-common/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.borja.nosqlbridge</groupId>
        <artifactId>bridge-runtime</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>bridge-common</artifactId>
    <dependencies>
        <dependency>
            <groupId>tools.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
    </dependencies>
</project>
```

## bridge-mongodb/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.borja.nosqlbridge</groupId>
        <artifactId>bridge-runtime</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>bridge-mongodb</artifactId>
    <dependencies>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-common</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-mongodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
    </dependencies>
</project>
```

## bridge-dynamodb/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.borja.nosqlbridge</groupId>
        <artifactId>bridge-runtime</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>bridge-dynamodb</artifactId>
    <dependencies>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-common</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>dynamodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
    </dependencies>
</project>
```

## bridge-sqs/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.borja.nosqlbridge</groupId>
        <artifactId>bridge-runtime</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>bridge-sqs</artifactId>
    <dependencies>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-common</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>sqs</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
    </dependencies>
</project>
```

## bridge-worker/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.borja.nosqlbridge</groupId>
        <artifactId>bridge-runtime</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>bridge-worker</artifactId>
    <dependencies>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-common</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-mongodb</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-dynamodb</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-sqs</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
    <build>
        <finalName>bridge-worker</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <mainClass>dev.borja.nosqlbridge.worker.BridgeRuntimeApplication</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## bridge-orchestrator/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>dev.borja.nosqlbridge</groupId>
        <artifactId>bridge-runtime</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>bridge-orchestrator</artifactId>
    <dependencies>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-common</artifactId>
        </dependency>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-mongodb</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>dev.borja.nosqlbridge</groupId>
            <artifactId>bridge-sqs</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
</project>
```
