package semantics.extension;

import org.codehaus.jackson.map.ObjectMapper;
import org.neo4j.graphdb.*;
import org.neo4j.logging.Log;
import org.openrdf.model.IRI;
import org.openrdf.model.Literal;
import org.openrdf.model.Resource;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.SimpleValueFactory;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.Rio;

import javax.ws.rs.*;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by jbarrasa on 08/09/2016.
 */
@Path("/")
public class RDFEndpoint {

    @Context
    public Log log;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static RDFFormat[] availableParsers = new RDFFormat[]{RDFFormat.RDFXML, RDFFormat.JSONLD, RDFFormat.TURTLE,
            RDFFormat.NTRIPLES, RDFFormat.TRIG};

    @POST
    @Path("/cypher")
    @Produces({"application/rdf+xml", "text/plain", "text/turtle", "text/n3", "application/trix", "application/x-trig",
            "application/ld+json"})
    public Response cypherOnPlainLPG(@Context GraphDatabaseService gds,
                                     @HeaderParam("accept") String acceptHeaderParam, String body) {
        return Response.ok().entity(new StreamingOutput() {
            @Override
            public void write(OutputStream outputStream) throws IOException, WebApplicationException {

                try (Transaction tx = gds.beginTx()) {
                    Result result = gds.execute(removeQuotes(body));
                    Set<Long> serializedNodes = new HashSet<Long>();
                    RDFWriter writer = Rio.createWriter(getFormat(acceptHeaderParam), outputStream);
                    SimpleValueFactory valueFactory = SimpleValueFactory.getInstance();
                    String baseVocabNS = "neo4j://vocabulary#";
                    String baseIndivNS = "neo4j://indiv#";
                    writer.handleNamespace("neovoc", baseVocabNS);
                    writer.handleNamespace("neoind", baseIndivNS);
                    writer.startRDF();
                    boolean doneOnce = false;
                    while (result.hasNext()) {
                        Map<String, Object> row = result.next();
                        Set<Map.Entry<String, Object>> entries = row.entrySet();
                        for(Map.Entry<String, Object> entry : entries){
                            Object o = entry.getValue();
                            if(o instanceof Node){
                                Node node = (Node) o;
                                if(!serializedNodes.contains(node.getId())) {
                                    processNodeInLPG(writer, valueFactory, baseVocabNS, baseIndivNS, node);
                                    serializedNodes.add(node.getId());
                                }
                            } else if (o instanceof Relationship){
                                processRelationshipInLPG(writer, valueFactory, baseVocabNS, baseIndivNS, (Relationship) o);
                            }
                        }
                    }
                    writer.endRDF();
                    result.close();
                }

            }
        }).build();
    }

    private String removeQuotes(String str) {
        if (str.matches("^(\"|').*(\"|')$")){
            return str.substring(1,str.length()-1);
        }
        return str;
    }

    private void processRelationshipInLPG(RDFWriter writer, SimpleValueFactory valueFactory, String baseVocabNS, String baseIndivNS, Relationship r) {

        IRI subject = valueFactory.createIRI(baseIndivNS, String.valueOf(r.getStartNode().getId()));
        IRI predicate = valueFactory.createIRI(baseVocabNS, r.getType().name());
        IRI object = valueFactory.createIRI(baseIndivNS, String.valueOf(r.getEndNode().getId()));
        writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
    }

    private void processNodeInLPG(RDFWriter writer, SimpleValueFactory valueFactory, String baseVocabNS, String baseIndivNS, Node node) {
        Iterable<Label> nodeLabels = node.getLabels();
        for (Label label : nodeLabels) {
            writer.handleStatement(
                    valueFactory.createStatement(valueFactory.createIRI(baseIndivNS, String.valueOf(node.getId())),
                            RDF.TYPE,
                            valueFactory.createIRI(baseVocabNS, label.name())));

        }
        Map<String, Object> allProperties = node.getAllProperties();
        for (String key : allProperties.keySet()) {
            IRI subject = valueFactory.createIRI(baseIndivNS, String.valueOf(node.getId()));
            IRI predicate = valueFactory.createIRI(baseVocabNS, key);
            Object propertyValueObject = allProperties.get(key);
            if (propertyValueObject instanceof Object[]) {
                for (int i = 0; i < ((Object[]) propertyValueObject).length; i++) {
                    Literal object = createTypedLiteral(valueFactory, ((Object[]) propertyValueObject)[i]);
                    writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                }
            } else {
                Literal object = createTypedLiteral(valueFactory, propertyValueObject);
                writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
            }

        }
    }

    @POST
    @Path("/cypheronrdf")
    @Produces({"application/rdf+xml", "text/plain", "text/turtle", "text/n3", "application/trix", "application/x-trig",
            "application/ld+json"})
    public Response cypherOnImportedRDF(@Context GraphDatabaseService gds,
                                        @HeaderParam("accept") String acceptHeaderParam, String body) {
        return Response.ok().entity(new StreamingOutput() {
            @Override
            public void write(OutputStream outputStream) throws IOException, WebApplicationException {

                Map<String,String> namespaces = getNamespacesFromDB(gds);

                try (Transaction tx = gds.beginTx()) {
                    Result result = gds.execute(removeQuotes(body));
                    Set<String> serializedNodes = new HashSet<String>();
                    RDFWriter writer = Rio.createWriter(getFormat(acceptHeaderParam), outputStream);
                    SimpleValueFactory valueFactory = SimpleValueFactory.getInstance();
                    String baseVocabNS = "neo4j://vocabulary#";
                    writer.handleNamespace("neovoc", baseVocabNS);
                    writer.startRDF();
                    boolean doneOnce = false;
                    while (result.hasNext()) {
                        Map<String, Object> row = result.next();
                        Set<Map.Entry<String, Object>> entries = row.entrySet();
                        for(Map.Entry<String, Object> entry : entries){
                            Object o = entry.getValue();
                            if(o instanceof Node){
                                Node node = (Node) o;
                                if(!serializedNodes.contains(node.getProperty("uri").toString())) {
                                    processNode(namespaces, writer, valueFactory, baseVocabNS, node);
                                    serializedNodes.add(node.getProperty("uri").toString());
                                }
                            } else if (o instanceof Relationship){
                                processRelationship(namespaces, writer, valueFactory, baseVocabNS, (Relationship) o);
                            }
                        }
                    }
                    writer.endRDF();
                    result.close();
                }

            }
        }).build();
    }

    private void processRelationship(Map<String, String> namespaces, RDFWriter writer, SimpleValueFactory valueFactory, String baseVocabNS, Relationship rel) {
        Resource subject = buildSubject(rel.getStartNode().getProperty("uri").toString(), valueFactory);
        IRI predicate = valueFactory.createIRI(buildURI(baseVocabNS, rel.getType().name(), namespaces));
        Resource object = buildSubject(rel.getEndNode().getProperty("uri").toString(), valueFactory);
        writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
    }

    private void processNode(Map<String, String> namespaces, RDFWriter writer, SimpleValueFactory valueFactory, String baseVocabNS, Node node) {
        Iterable<Label> nodeLabels = node.getLabels();
        for (Label label : nodeLabels) {
            //Exclude the URI, Resource and Bnode categories created by the importer to emulate RDF
            if (!(label.name().equals("Resource") || label.name().equals("URI") ||
                    label.name().equals("BNode"))) {
                writer.handleStatement(
                        valueFactory.createStatement(buildSubject(node.getProperty("uri").toString(), valueFactory),
                                RDF.TYPE,
                                valueFactory.createIRI(buildURI(baseVocabNS, label.name(), namespaces))));

            }
        }
        Map<String, Object> allProperties = node.getAllProperties();
        for (String key : allProperties.keySet()) {
            if (!key.equals("uri")) {
                Resource subject = buildSubject(node.getProperty("uri").toString(), valueFactory);
                IRI predicate = valueFactory.createIRI(buildURI(baseVocabNS, key, namespaces));
                Object propertyValueObject = allProperties.get(key);
                if (propertyValueObject instanceof Object[]) {
                    for (int i = 0; i < ((Object[]) propertyValueObject).length; i++) {
                        Literal object = createTypedLiteral(valueFactory, ((Object[]) propertyValueObject)[i]);
                        writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                    }
                } else {
                    Literal object = createTypedLiteral(valueFactory, propertyValueObject);
                    writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                }
            }

        }
    }

    private Resource buildSubject(String id, ValueFactory vf) {
        Resource result;
        try{
            result = vf.createIRI(id);
        } catch(IllegalArgumentException e){
            result = vf.createBNode(id);
        }

        return result;
    }


    @GET
    @Path("/describe/uri")
    @Produces({"application/rdf+xml", "text/plain", "text/turtle", "text/n3", "application/trix", "application/x-trig",
            "application/ld+json"})
    public Response nodebyuri(@Context GraphDatabaseService gds, @QueryParam("nodeuri") String idParam,
                              @QueryParam("excludeContext") String excludeContextParam,
                              @HeaderParam("accept") String acceptHeaderParam) {
        return Response.ok().entity(new StreamingOutput() {
            @Override
            public void write(OutputStream outputStream) throws IOException, WebApplicationException {

                Map<String,String> namespaces = getNamespacesFromDB(gds);

                String queryWithContext = "MATCH (x:Resource {uri:{theuri}}) " +
                        "OPTIONAL MATCH (x)-[r]-(val:Resource) WHERE exists(val.uri)\n" +
                        "RETURN x, r, val.uri AS value";

                String queryNoContext = "MATCH (x:Resource {uri:{theuri}}) " +
                        "RETURN x, NULL AS r, NULL AS value";

                Map<String, Object> params = new HashMap<>();
                params.put("theuri", idParam);
                try (Transaction tx = gds.beginTx()) {
                    Result result = gds.execute((excludeContextParam != null ? queryNoContext : queryWithContext), params);


                    RDFWriter writer = Rio.createWriter(getFormat(acceptHeaderParam), outputStream);
                    SimpleValueFactory valueFactory = SimpleValueFactory.getInstance();
                    String baseVocabNS = "neo4j://vocabulary#";
                    writer.handleNamespace("neovoc", baseVocabNS);
                    writer.startRDF();
                    boolean doneOnce = false;
                    while (result.hasNext()) {
                        Map<String, Object> row = result.next();
                        if (!doneOnce) {
                            //Output only once the props of the selected node as literal properties
                            Node node = (Node) row.get("x");
                            Iterable<Label> nodeLabels = node.getLabels();
                            for (Label label : nodeLabels) {
                                //Exclude the URI, Resource and Bnode categories created by the importer to emulate RDF
                                if (!(label.name().equals("Resource") || label.name().equals("URI") ||
                                        label.name().equals("BNode"))) {
                                    writer.handleStatement(
                                            valueFactory.createStatement(valueFactory.createIRI(idParam.toString()),
                                                    RDF.TYPE,
                                                    valueFactory.createIRI(buildURI(baseVocabNS, label.name(), namespaces))));
                                }
                            }
                            Map<String, Object> allProperties = node.getAllProperties();
                            for (String key : allProperties.keySet()) {
                                if (!key.equals("uri")) {
                                    IRI subject = valueFactory.createIRI(idParam.toString());
                                    IRI predicate = valueFactory.createIRI(buildURI(baseVocabNS, key, namespaces));
                                    Object propertyValueObject = allProperties.get(key);
                                    if (propertyValueObject instanceof Object[]) {
                                        for (int i = 0; i < ((Object[]) propertyValueObject).length; i++) {
                                            Literal object = createTypedLiteral(valueFactory, ((Object[]) propertyValueObject)[i]);
                                            writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                                        }
                                    } else {
                                        Literal object = createTypedLiteral(valueFactory, propertyValueObject);
                                        writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                                    }
                                }

                            }
                            doneOnce = true;
                        }
                        Relationship rel = (Relationship) row.get("r");
                        if (rel != null) {
                            IRI subject = valueFactory.createIRI(rel.getStartNode().getProperty("uri").toString());
                            IRI predicate = valueFactory.createIRI(buildURI(baseVocabNS, rel.getType().name(), namespaces));
                            IRI object = valueFactory.createIRI(rel.getEndNode().getProperty("uri").toString());
                            writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                        }
                    }
                    writer.endRDF();
                    result.close();
                }

            }
        }).build();
    }

    private Map<String,String> getNamespacesFromDB(GraphDatabaseService graphdb) {
        Result nslist = graphdb.execute("MATCH (n:NamespacePrefixDefinition) \n" +
                "UNWIND keys(n) AS namespace\n" +
                "RETURN namespace, n[namespace] as prefix");
        Map<String, String> result = new HashMap<String, String>();
        while (nslist.hasNext()){
            Map<String, Object> ns = nslist.next();
            result.put((String)ns.get("namespace"),(String)ns.get("prefix"));
        }
        return result;
    }

    private String buildURI(String baseVocabNS, String name, Map<String, String> namespaces) {
        //TODO
        // if uri then return as is
        Pattern regex = Pattern.compile("^(ns\\d+)_(.*)$");
        Matcher matcher = regex.matcher(name);
        if (matcher.matches()){
            String prefix = matcher.group(1);
            String uriPrefix = getKeyFromValue(prefix, namespaces);
            //if namespace but does not exist, then ??? Default to default

            String localName = matcher.group(2);
            return uriPrefix + localName;
        } else if (name.startsWith("http")){
            //make this test better
            return name;
        } else {
            return baseVocabNS + name;
        }

    }

    private String getKeyFromValue(String prefix, Map<String, String> namespaces) {
        for(String key: namespaces.keySet()){
            if(namespaces.get(key).equals(prefix)){
                return key;
            }
        }
        return null;
    }

    private String getPrefix(String namespace, Map<String,String> namespaces) {
        if (namespaces.containsKey(namespace)){
            return namespaces.get(namespace);
        } else{
            return namespace;
        }
    }

    @GET
    @Path("/describe/id")
    @Produces({"application/rdf+xml", "text/plain", "text/turtle", "text/n3", "application/trix", "application/x-trig",
            "application/ld+json"})
    public Response nodebyid(@Context GraphDatabaseService gds, @QueryParam("nodeid") Long idParam,
                             @QueryParam("excludeContext") String excludeContextParam,
                             @HeaderParam("accept") String acceptHeaderParam) {
        return Response.ok().entity(new StreamingOutput() {
            @Override
            public void write(OutputStream outputStream) throws IOException, WebApplicationException {

                String queryWithContext = " MATCH (x) WHERE ID(x) = {theid} " +
                        " OPTIONAL MATCH (x)-[r]-(val) " +
                        " RETURN x, r, ID(val) AS value ";

                String queryNoContext = " MATCH (x) WHERE ID(x) = {theid} " +
                        " RETURN x, NULL AS r, NULL AS value ";

                Map<String, Object> params = new HashMap<>();
                params.put("theid", idParam);
                try (Transaction tx = gds.beginTx()) {
                    Result result = gds.execute((excludeContextParam != null ? queryNoContext : queryWithContext), params);


                    RDFWriter writer = Rio.createWriter(getFormat(acceptHeaderParam), outputStream);
                    SimpleValueFactory valueFactory = SimpleValueFactory.getInstance();
                    String baseVocabNS = "neo4j://vocabulary#";
                    String baseIndivNS = "neo4j://indiv#";
                    writer.handleNamespace("neovoc", baseVocabNS);
                    writer.handleNamespace("neoind", baseIndivNS);
                    writer.startRDF();
                    boolean doneOnce = false;
                    while (result.hasNext()) {
                        Map<String, Object> row = result.next();
                        if (!doneOnce) {
                            //Output only once the props of the selected node as literal properties
                            Node node = (Node) row.get("x");
                            Iterable<Label> nodeLabels = node.getLabels();
                            for (Label label : nodeLabels) {
                                writer.handleStatement(
                                        valueFactory.createStatement(valueFactory.createIRI(baseIndivNS, idParam.toString()),
                                                RDF.TYPE,
                                                valueFactory.createIRI(baseVocabNS, label.name())));
                            }
                            Map<String, Object> allProperties = node.getAllProperties();
                            for (String key : allProperties.keySet()) {
                                IRI subject = valueFactory.createIRI(baseIndivNS, idParam.toString());
                                IRI predicate = valueFactory.createIRI(baseVocabNS, key);
                                Object propertyValueObject = allProperties.get(key);
                                if (propertyValueObject instanceof Object[]) {
                                    for (int i = 0; i < ((Object[]) propertyValueObject).length; i++) {
                                        Literal object = createTypedLiteral(valueFactory, ((Object[]) propertyValueObject)[i]);
                                        writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                                    }
                                } else {
                                    Literal object = createTypedLiteral(valueFactory, propertyValueObject);
                                    writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                                }

                            }
                            doneOnce = true;
                        }
                        Relationship rel = (Relationship) row.get("r");
                        if (rel != null) {
                            // output each relationship and connected node as an object property
                            IRI subject = valueFactory.createIRI(baseIndivNS, String.valueOf(rel.getStartNode().getId()));
                            IRI predicate = valueFactory.createIRI(baseVocabNS, rel.getType().name());
                            IRI object = valueFactory.createIRI(baseIndivNS, String.valueOf(rel.getEndNode().getId()));
                            writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                        }
                    }

                    writer.endRDF();
                    result.close();
                }
            }
        }).build();
    }


    @GET
    @Path("/ping")
    public Response ping() throws IOException {
        Map<String, String> results = new HashMap<String,String>(){{
            put("ping","here!");
        }};
        return Response.ok().entity(objectMapper.writeValueAsString(results)).build();
    }

    @GET
    @Path("/onto")
    //@Consumes(MediaType.TEXT_PLAIN)
    public Response exportOnto(@Context GraphDatabaseService gds, String body) {
        return Response.ok().entity(new StreamingOutput() {
            @Override
            public void write(OutputStream outputStream) throws IOException, WebApplicationException {
                Result res = gds.execute("call apoc.meta.graph ");
                Map<String, Object> next = res.next();
                List<Relationship> relationshipList = (List<Relationship>) next.get("relationships");
                for(Relationship r: relationshipList){
                    System.out.println(r.getStartNode().getLabels().iterator().next().name());
                    System.out.println(r.getEndNode().getLabels().iterator().next().name());
                    System.out.println(r.getType().name());
                }
                ///
                ///
                String query = "";
                Result result = gds.execute(query);
                RDFWriter writer = Rio.createWriter(RDFFormat.JSONLD, outputStream);
                SimpleValueFactory valueFactory = SimpleValueFactory.getInstance();
                String baseNS = "http://neo4j.com/";
                writer.startRDF();
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    IRI subject = valueFactory.createIRI(baseNS, (String) row.get("subject"));
                    IRI predicate = valueFactory.createIRI(baseNS, (String) row.get("predicate"));
                    Literal object = valueFactory.createLiteral((String) row.get("object"));
                    writer.handleStatement(valueFactory.createStatement(subject, predicate, object));
                }
                writer.endRDF();
                result.close();
            }
        }).build();
    }

    private Literal createTypedLiteral(SimpleValueFactory valueFactory, Object value) {
        Literal result = null;
        if (value instanceof String) {
            result = valueFactory.createLiteral((String) value);
        } else if (value instanceof Integer) {
            result = valueFactory.createLiteral((Integer) value);
        } else if (value instanceof Long) {
            result = valueFactory.createLiteral((Long) value);
        } else if (value instanceof Float) {
            result = valueFactory.createLiteral((Float) value);
        } else if (value instanceof Double) {
            result = valueFactory.createLiteral((Double) value);
        } else if (value instanceof Boolean) {
            result = valueFactory.createLiteral((Boolean) value);
        } else {
            // default?
            result = valueFactory.createLiteral("" + value);
        }

        return result;
    }


    private RDFFormat getFormat(String mimetype) {
        if (mimetype != null) {
            log.info("mimetipe in request: " + mimetype);
            for (RDFFormat parser : availableParsers) {
                if (parser.getMIMETypes().contains(mimetype)) {
                    log.info("parser to be used: " + parser.getDefaultMIMEType());
                    return parser;
                }
            }
        }

        log.info("Unrecognized serialization in accept header param. Defaulting to JSON-LD serialization");

        return RDFFormat.JSONLD;

    }
}

//curl -i -d 'match ()-[r]->() return toString(id(startNode(r))) as subject, type(r) as predicate, toString(id(endNode(r))) as oct limit 10' http://localhost:7474/rdf/export -H accept:text/plain -H content-type:text/plain

