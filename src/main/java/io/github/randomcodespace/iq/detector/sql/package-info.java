/**
 * SQL and migration detectors.
 * <p>
 * Extracts schema-level entities (tables, views, schemas) from raw SQL DDL and
 * framework-specific migration files (Flyway, Liquibase, Alembic, Rails, Prisma),
 * and links migrations to the SQL entities they create or alter.
 */
package io.github.randomcodespace.iq.detector.sql;
