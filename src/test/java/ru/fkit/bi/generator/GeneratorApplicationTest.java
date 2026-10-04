package ru.fkit.bi.generator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:testdb")
class GeneratorApplicationTest { @Test void contextLoads() {} }
