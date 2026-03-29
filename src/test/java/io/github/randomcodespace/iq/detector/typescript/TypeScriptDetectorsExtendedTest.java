package io.github.randomcodespace.iq.detector.typescript;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypeScriptDetectorsExtendedTest {

    // ==================== FastifyRouteDetector ====================
    @Nested
    class FastifyExtended {
        private final FastifyRouteDetector d = new FastifyRouteDetector();

        @Test
        void detectsFastifyRoutes() {
            String code = """
                    fastify.get('/items', async (request, reply) => {
                      return db.items.findAll();
                    });
                    fastify.post('/items', async (request, reply) => {
                      return db.items.create(request.body);
                    });
                    fastify.put('/items/:id', async (request, reply) => {
                      return db.items.update(request.params.id, request.body);
                    });
                    fastify.delete('/items/:id', async (request, reply) => {
                      return db.items.delete(request.params.id);
                    });
                    """;
            var r = d.detect(ctx("typescript", code));
            assertTrue(r.nodes().size() >= 4);
        }

        @Test
        void detectsRouteMethod() {
            String code = """
                    fastify.route({
                      method: 'GET',
                      url: '/api/health',
                      handler: async (request, reply) => {
                        return { status: 'ok' };
                      }
                    });
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void emptyReturnsEmpty() {
            var r = d.detect(ctx("typescript", ""));
            assertTrue(r.nodes().isEmpty());
        }
    }

    // ==================== MongooseORMDetector ====================
    @Nested
    class MongooseExtended {
        private final MongooseORMDetector d = new MongooseORMDetector();

        @Test
        void detectsSchemaAndModel() {
            String code = """
                    const userSchema = new mongoose.Schema({
                      name: { type: String, required: true },
                      email: { type: String, unique: true },
                      age: Number
                    });
                    const User = mongoose.model('User', userSchema);
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsSchemaWithMethods() {
            String code = """
                    const Schema = mongoose.Schema;
                    const postSchema = new Schema({
                      title: String,
                      body: String,
                      author: { type: Schema.Types.ObjectId, ref: 'User' }
                    });
                    postSchema.index({ title: 'text' });
                    const Post = mongoose.model('Post', postSchema);
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsMongooseConnect() {
            String code = """
                    mongoose.connect('mongodb://localhost:27017/myapp');
                    const userSchema = new mongoose.Schema({
                      name: String,
                      email: String
                    });
                    const User = mongoose.model('User', userSchema);
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    // ==================== PassportJwtDetector ====================
    @Nested
    class PassportJwtExtended {
        private final PassportJwtDetector d = new PassportJwtDetector();

        @Test
        void detectsPassportJwtStrategy() {
            String code = """
                    passport.use(new JwtStrategy({
                      jwtFromRequest: ExtractJwt.fromAuthHeaderAsBearerToken(),
                      secretOrKey: process.env.JWT_SECRET
                    }, async (payload, done) => {
                      const user = await User.findById(payload.sub);
                      done(null, user);
                    }));
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }

        @Test
        void detectsPassportLocalStrategy() {
            String code = """
                    passport.use(new LocalStrategy(
                      { usernameField: 'email' },
                      async (email, password, done) => {
                        const user = await User.findOne({ email });
                        done(null, user);
                      }
                    ));
                    passport.serializeUser((user, done) => done(null, user.id));
                    passport.deserializeUser((id, done) => User.findById(id).then(u => done(null, u)));
                    """;
            var r = d.detect(ctx("typescript", code));
            assertFalse(r.nodes().isEmpty());
        }
    }

    private static DetectorContext ctx(String language, String content) {
        return DetectorTestUtils.contextFor(language, content);
    }
}
