package io.github.randomcodespace.iq.detector.iac;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IacDetectorsExtendedTest {

    // ==================== TerraformDetector ====================
    @Nested
    class TerraformExtended {
        private final TerraformDetector d = new TerraformDetector();

        @Test
        void detectsMultipleResources() {
            String code = """
                    resource "aws_instance" "web" {
                      ami           = "ami-12345"
                      instance_type = "t3.micro"
                      tags = {
                        Name = "web-server"
                      }
                    }

                    resource "aws_s3_bucket" "data" {
                      bucket = "my-data-bucket"
                    }

                    resource "aws_lambda_function" "api" {
                      function_name = "api-handler"
                      runtime       = "python3.11"
                    }

                    resource "aws_dynamodb_table" "users" {
                      name           = "users"
                      hash_key       = "id"
                    }
                    """;
            var r = d.detect(DetectorTestUtils.contextFor("terraform", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsDataSources() {
            String code = """
                    data "aws_ami" "ubuntu" {
                      most_recent = true
                      owners      = ["099720109477"]
                    }

                    data "aws_vpc" "default" {
                      default = true
                    }
                    """;
            var r = d.detect(DetectorTestUtils.contextFor("terraform", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsModulesAndVariables() {
            String code = """
                    module "vpc" {
                      source  = "terraform-aws-modules/vpc/aws"
                      version = "5.0.0"
                    }

                    variable "region" {
                      type    = string
                      default = "us-east-1"
                    }

                    output "vpc_id" {
                      value = module.vpc.vpc_id
                    }
                    """;
            var r = d.detect(DetectorTestUtils.contextFor("terraform", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsProvider() {
            String code = """
                    terraform {
                      required_version = ">= 1.0"
                      required_providers {
                        aws = {
                          source  = "hashicorp/aws"
                          version = "~> 5.0"
                        }
                      }
                    }

                    provider "aws" {
                      region = "us-east-1"
                    }
                    """;
            var r = d.detect(DetectorTestUtils.contextFor("terraform", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void emptyContentReturnsEmpty() {
            var r = d.detect(DetectorTestUtils.contextFor("terraform", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== DockerfileDetector ====================
    @Nested
    class DockerfileExtended {
        private final DockerfileDetector d = new DockerfileDetector();

        @Test
        void detectsMultiStage() {
            String code = """
                    FROM maven:3.9-eclipse-temurin-21 AS builder
                    WORKDIR /app
                    COPY pom.xml .
                    RUN mvn dependency:go-offline
                    COPY src ./src
                    RUN mvn package -DskipTests

                    FROM eclipse-temurin:21-jre-alpine AS runtime
                    WORKDIR /app
                    COPY --from=builder /app/target/*.jar app.jar
                    EXPOSE 8080
                    HEALTHCHECK --interval=30s CMD curl -f http://localhost:8080/actuator/health || exit 1
                    CMD ["java", "-jar", "app.jar"]
                    """;
            var r = d.detect(new DetectorContext("Dockerfile", "dockerfile", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsEnvAndArg() {
            String code = """
                    FROM node:18
                    ARG NODE_ENV=production
                    ENV PORT=3000
                    ENV APP_NAME=myapp
                    WORKDIR /usr/src/app
                    COPY package*.json ./
                    RUN npm ci
                    COPY . .
                    EXPOSE 3000
                    USER node
                    ENTRYPOINT ["node", "server.js"]
                    """;
            var r = d.detect(new DetectorContext("Dockerfile", "dockerfile", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void emptyReturnsEmpty() {
            var r = d.detect(new DetectorContext("Dockerfile", "dockerfile", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== BicepDetector ====================
    @Nested
    class BicepExtended {
        private final BicepDetector d = new BicepDetector();

        @Test
        void detectsMultipleResources() {
            String code = """
                    param location string = resourceGroup().location
                    param appName string

                    resource storageAccount 'Microsoft.Storage/storageAccounts@2023-01-01' = {
                      name: '${appName}storage'
                      location: location
                      kind: 'StorageV2'
                      sku: { name: 'Standard_LRS' }
                    }

                    resource appServicePlan 'Microsoft.Web/serverfarms@2023-01-01' = {
                      name: '${appName}-plan'
                      location: location
                      sku: { name: 'F1' }
                    }

                    resource webApp 'Microsoft.Web/sites@2023-01-01' = {
                      name: appName
                      location: location
                      properties: {
                        serverFarmId: appServicePlan.id
                      }
                    }

                    output storageId string = storageAccount.id
                    """;
            var r = d.detect(DetectorTestUtils.contextFor("bicep", code));
            assertTrue(r.nodes().size() >= 3);
        }

        @Test
        void detectsModules() {
            String code = """
                    module vnet './modules/vnet.bicep' = {
                      name: 'vnet-deploy'
                      params: {
                        location: location
                      }
                    }
                    """;
            var r = d.detect(DetectorTestUtils.contextFor("bicep", code));
            assertFalse(r.nodes().isEmpty());
        }
    }
}
