"""Tests for structured parsers — Gradle, Properties, SQL, XML."""

from __future__ import annotations

from osscodeiq.parsing.structured.gradle_parser import GradleParser
from osscodeiq.parsing.structured.properties_parser import PropertiesParser
from osscodeiq.parsing.structured.sql_parser import SqlParser
from osscodeiq.parsing.structured.xml_parser import XmlParser


# ---------- GradleParser ----------


class TestGradleParser:
    def test_basic_dependency(self):
        content = b"""
plugins {
    id 'java'
}

group = 'com.example'
version = '1.0.0'

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web:3.1.0'
    testImplementation 'junit:junit:4.13.2'
}
"""
        result = GradleParser().parse(content, "build.gradle")
        assert result["type"] == "gradle"
        assert result["file"] == "build.gradle"
        assert result["group"] == "com.example"
        assert result["version"] == "1.0.0"
        assert len(result["dependencies"]) == 2
        dep = result["dependencies"][0]
        assert dep["group"] == "org.springframework.boot"
        assert dep["artifact"] == "spring-boot-starter-web"
        assert dep["version"] == "3.1.0"

    def test_plugins_extracted(self):
        content = b"""
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.1.0'
    id("com.github.node-gradle.node")
}
"""
        result = GradleParser().parse(content, "build.gradle")
        assert "java" in result["plugins"]
        assert "org.springframework.boot" in result["plugins"]
        assert "com.github.node-gradle.node" in result["plugins"]

    def test_multiple_dep_configs(self):
        content = b"""
dependencies {
    api 'com.google.guava:guava:31.0'
    compileOnly 'org.projectlombok:lombok:1.18.24'
    runtimeOnly 'mysql:mysql-connector-java:8.0.33'
    kapt 'com.google.dagger:dagger-compiler:2.44'
}
"""
        result = GradleParser().parse(content, "build.gradle.kts")
        assert len(result["dependencies"]) == 4
        configs = [d["configuration"] for d in result["dependencies"]]
        assert "api" in configs
        assert "compileOnly" in configs
        assert "runtimeOnly" in configs
        assert "kapt" in configs

    def test_empty_file(self):
        result = GradleParser().parse(b"", "build.gradle")
        assert result["type"] == "gradle"
        assert result["dependencies"] == []
        assert result["plugins"] == []
        assert result["group"] is None
        assert result["version"] is None

    def test_kotlin_dsl_parentheses(self):
        content = b"""
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
}
"""
        result = GradleParser().parse(content, "build.gradle.kts")
        assert len(result["dependencies"]) == 1
        assert result["dependencies"][0]["artifact"] == "kotlin-stdlib"

    def test_dep_without_version(self):
        content = b"""
dependencies {
    implementation 'com.example:mylib'
}
"""
        result = GradleParser().parse(content, "build.gradle")
        dep = result["dependencies"][0]
        assert dep["group"] == "com.example"
        assert dep["artifact"] == "mylib"
        assert "version" not in dep


# ---------- PropertiesParser ----------


class TestPropertiesParser:
    def test_basic_key_value(self):
        content = b"db.host=localhost\ndb.port=5432\n"
        result = PropertiesParser().parse(content, "app.properties")
        assert result["type"] == "properties"
        assert result["data"]["db.host"] == "localhost"
        assert result["data"]["db.port"] == "5432"

    def test_colon_separator(self):
        content = b"key: value\n"
        result = PropertiesParser().parse(content, "test.properties")
        assert result["data"]["key"] == "value"

    def test_comments_ignored(self):
        content = b"# This is a comment\n! Another comment\nkey=val\n"
        result = PropertiesParser().parse(content, "test.properties")
        assert len(result["data"]) == 1
        assert result["data"]["key"] == "val"

    def test_blank_lines_ignored(self):
        content = b"a=1\n\n\nb=2\n"
        result = PropertiesParser().parse(content, "test.properties")
        assert len(result["data"]) == 2

    def test_continuation_lines(self):
        content = b"long.value=hello \\\nworld\n"
        result = PropertiesParser().parse(content, "test.properties")
        assert result["data"]["long.value"] == "hello world"

    def test_empty_file(self):
        result = PropertiesParser().parse(b"", "empty.properties")
        assert result["data"] == {}

    def test_key_without_value(self):
        content = b"standalone_key\n"
        result = PropertiesParser().parse(content, "test.properties")
        assert result["data"]["standalone_key"] == ""

    def test_spaces_around_separator(self):
        content = b"key = value with spaces\n"
        result = PropertiesParser().parse(content, "test.properties")
        assert result["data"]["key"] == "value with spaces"


# ---------- SqlParser ----------


class TestSqlParser:
    def test_create_table(self):
        content = b"""
CREATE TABLE users (
    id INT PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255)
);
"""
        result = SqlParser().parse(content, "V1__init.sql")
        assert result["type"] == "sql"
        assert "users" in result["tables_created"]
        assert result["statement_count"] >= 1

    def test_alter_table(self):
        content = b"ALTER TABLE users ADD COLUMN age INT;\n"
        result = SqlParser().parse(content, "V2__alter.sql")
        assert "users" in result["tables_altered"]

    def test_drop_table(self):
        content = b"DROP TABLE IF EXISTS temp_data;\n"
        result = SqlParser().parse(content, "V3__drop.sql")
        assert "temp_data" in result["tables_dropped"]

    def test_multiple_statements(self):
        content = b"""
CREATE TABLE orders (id INT PRIMARY KEY);
CREATE TABLE items (id INT PRIMARY KEY);
ALTER TABLE orders ADD COLUMN total DECIMAL;
"""
        result = SqlParser().parse(content, "migration.sql")
        assert len(result["tables_created"]) == 2
        assert len(result["tables_altered"]) == 1
        assert result["statement_count"] >= 3

    def test_empty_sql(self):
        result = SqlParser().parse(b"", "empty.sql")
        assert result["tables_created"] == []
        assert result["statement_count"] == 0

    def test_create_table_if_not_exists(self):
        content = b"CREATE TABLE IF NOT EXISTS config (key TEXT, value TEXT);\n"
        result = SqlParser().parse(content, "V4.sql")
        assert "config" in result["tables_created"]


# ---------- XmlParser ----------


class TestXmlParser:
    def test_generic_xml(self):
        content = b"""<?xml version="1.0"?>
<root>
    <child attr="value"/>
</root>
"""
        result = XmlParser().parse(content, "config.xml")
        assert result["type"] == "xml"
        assert result["root_tag"] == "root"

    def test_pom_xml(self):
        content = b"""<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    <dependencies>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <modules>
        <module>core</module>
        <module>web</module>
    </modules>
</project>
"""
        result = XmlParser().parse(content, "pom.xml")
        assert result["type"] == "pom"
        assert result["groupId"] == "com.example"
        assert result["artifactId"] == "my-app"
        assert result["version"] == "1.0.0"
        assert result["packaging"] == "jar"
        assert len(result["dependencies"]) == 1
        dep = result["dependencies"][0]
        assert dep["groupId"] == "junit"
        assert dep["scope"] == "test"
        assert result["modules"] == ["core", "web"]

    def test_pom_with_parent(self):
        content = b"""<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <parent>
        <groupId>com.example</groupId>
        <artifactId>parent-pom</artifactId>
        <version>2.0.0</version>
    </parent>
    <artifactId>child-module</artifactId>
</project>
"""
        result = XmlParser().parse(content, "pom.xml")
        assert result["type"] == "pom"
        assert result["parent"] is not None
        assert result["parent"]["groupId"] == "com.example"
        # groupId inherited from parent
        assert result["groupId"] == "com.example"

    def test_spring_xml(self):
        content = b"""<?xml version="1.0"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:context="http://www.springframework.org/schema/context">
    <bean id="myService" class="com.example.MyService" scope="singleton"/>
    <context:component-scan base-package="com.example.controllers"/>
</beans>
"""
        result = XmlParser().parse(content, "applicationContext.xml")
        assert result["type"] == "spring_xml"
        assert len(result["beans"]) == 1
        assert result["beans"][0]["id"] == "myService"
        assert result["beans"][0]["class"] == "com.example.MyService"
        assert "com.example.controllers" in result["component_scans"]

    def test_invalid_xml(self):
        content = b"<not valid xml>>"
        result = XmlParser().parse(content, "bad.xml")
        assert result.get("error") == "invalid_xml"

    def test_empty_pom(self):
        content = b"""<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
</project>
"""
        result = XmlParser().parse(content, "pom.xml")
        assert result["type"] == "pom"
        assert result["dependencies"] == []
        assert result["modules"] == []
