# fixture-minimal

Three-file fixture exercising every phase-1 detector exactly once. Used by
the parity harness (`go/parity/`) to verify the Go binary's `index` output
matches the Java binary's on the same input.

| File | Detector hits |
|---|---|
| `UserController.java` | spring_rest (3 endpoints), generic_imports |
| `User.java` | jpa_entity, generic_imports |
| `models.py` | python.django_models (2 entities + 1 FK), python.flask_routes (3 endpoints across GET/POST), generic_imports |

No build files (no pom.xml, no requirements.txt) — the ServiceDetector lands
in phase 2 and would extend the expected output. Keep this fixture stable.
