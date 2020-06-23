package ebi.spot.neo4j2owl;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.kernel.impl.core.NodeProxy;
import org.neo4j.kernel.impl.core.RelationshipProxy;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Procedure;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by matentzn on 05/03/2018.
 * <p>
 */
public class OWL2OntologyExporter {

    @Context
    public GraphDatabaseService db;

    @Context
    public Log log;

    static OWLDataFactory df = OWLManager.getOWLDataFactory();
    //static IRIManager iriManager = new IRIManager();
    static N2OExportManager n2OEntityManager;
    static Set<String> qsls_with_no_matching_properties;


    static long start;


    private void log(Object msg) {
        log.info(msg.toString());
        System.out.println(msg + " " + getTimePassed());
    }

    private String getTimePassed() {
        long time = System.currentTimeMillis() - start;
        return ((double) time / 1000.0) + " sec";
    }

    @Procedure(mode = Mode.WRITE)
    public Stream<N2OReturnValue> exportOWL() throws Exception { //@Name("file") String fileName
        n2OEntityManager = new N2OExportManager();
        qsls_with_no_matching_properties = new HashSet<>();
        start = System.currentTimeMillis();
        try {
            OWLOntologyManager man = OWLManager.createOWLOntologyManager();

            OWLOntology o = man.createOntology();
            addEntities(o);
            addAnnotations(o);
            addRelation(o, N2OStatic.RELTYPE_SUBCLASSOF);
            addRelation(o, N2OStatic.RELTYPE_INSTANCEOF);
            for (String rel_qsl : getRelations(OWLAnnotationProperty.class)) {
                addRelation(o, rel_qsl);
            }
            for (String rel_qsl : getRelations(OWLObjectProperty.class)) {
                addRelation(o, rel_qsl);
            }
            for (String rel_qsl : getRelations(OWLDataProperty.class)) {
                addRelation(o, rel_qsl);
            }
            ByteArrayOutputStream os = new ByteArrayOutputStream(); //new FileOutputStream(new File(fileName))
            man.saveOntology(o, new RDFXMLDocumentFormat(), os);
            qsls_with_no_matching_properties.forEach(this::log);
            return Stream.of(new N2OReturnValue(os.toString(java.nio.charset.StandardCharsets.UTF_8.name()), o.getLogicalAxiomCount() + ""));
        } catch (Exception e) {
            e.printStackTrace();
            return Stream.of(new N2OReturnValue(e.getClass().getSimpleName(), getStackTrace(e)));
        }
    }

    public static String getStackTrace(final Throwable throwable) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw, true);
        throwable.printStackTrace(pw);
        return sw.getBuffer().toString();
    }

    private Set<String> getRelations(Class cl) {
        return n2OEntityManager.relationshipQSLs().stream().filter(k -> cl.isInstance(n2OEntityManager.getRelationshipByQSL(k))).collect(Collectors.toSet());
    }

    private void addRelation(OWLOntology o, String RELTYPE) throws N2OException {
        //log("addRelation():"+RELTYPE);
        //log(mapIdEntity);
        String cypher = String.format("MATCH (n:Entity)-[r:" + RELTYPE + "]->(x:Entity) Return n,r,x");
        Result s = db.execute(cypher);
        List<OWLOntologyChange> changes = new ArrayList<>();
        while (s.hasNext()) {
            Map<String, Object> r = s.next();
            //log(r);
            Long nid = ((NodeProxy) r.get("n")).getId();
            Long xid = ((NodeProxy) r.get("x")).getId();
            RelationshipProxy rp = (RelationshipProxy) r.get("r");

            OWLAxiom ax = createAxiom(n2OEntityManager.getEntity(nid), n2OEntityManager.getEntity(xid), RELTYPE);
            Set<OWLAnnotation> axiomAnnotations = getAxiomAnnotations(rp);
            changes.add(new AddAxiom(o, ax.getAnnotatedAxiom(axiomAnnotations)));
        }
        if (!changes.isEmpty()) {
            try {
                o.getOWLOntologyManager().applyChanges(changes);
            } catch (Exception e) {
                String msg = "";
                for (OWLOntologyChange c : changes) {
                    msg += c.toString() + "\n";
                }
                throw new N2OException(msg, e);
            }
        }
    }

    private Set<OWLAnnotation> getAxiomAnnotations(RelationshipProxy rp) {
        Set<OWLAnnotation> axiomAnnotations = new HashSet<>();
        Map<String, Object> rpros = rp.getAllProperties();
        for (String propertykey : rpros.keySet()) {
            OWLAnnotationProperty ap = getAnnotationProperty(propertykey);
            Object v = rpros.get(propertykey);
            if (v.getClass().isArray()) {
                for (Object val : toObjectArray(v)) {
                    OWLAnnotationValue value = getLiteral(val);
                    axiomAnnotations.add(df.getOWLAnnotation(ap, value));
                }
            } else {
                OWLAnnotationValue value = getLiteral(v);
                axiomAnnotations.add(df.getOWLAnnotation(ap, value));
            }
        }
        return axiomAnnotations;
    }

    private OWLAxiom createAxiom(OWLEntity e_from, OWLEntity e_to, String type) throws N2OException {

        if (type.equals(N2OStatic.RELTYPE_SUBCLASSOF)) {
            return df.getOWLSubClassOfAxiom((OWLClass) e_from, (OWLClass) e_to);
        } else if (type.equals(N2OStatic.RELTYPE_INSTANCEOF)) {
            return df.getOWLClassAssertionAxiom((OWLClass) e_to, (OWLIndividual) e_from);
        } else {
            OWLEntity p = n2OEntityManager.getRelationshipByQSL(type);
            if (p instanceof OWLObjectProperty) {
                if (e_from instanceof OWLClass) {
                    if (e_to instanceof OWLClass) {
                        return df.getOWLSubClassOfAxiom((OWLClass) e_from, df.getOWLObjectSomeValuesFrom((OWLObjectProperty) p, (OWLClass) e_to));
                    } else if (e_to instanceof OWLNamedIndividual) {
                        return df.getOWLSubClassOfAxiom((OWLClass) e_from, df.getOWLObjectHasValue((OWLObjectProperty) p, (OWLNamedIndividual) e_to));
                    } else {
                        log("Not deal with OWLClass-" + type + "-X");
                    }
                } else if (e_from instanceof OWLNamedIndividual) {
                    if (e_to instanceof OWLClass) {
                        return df.getOWLClassAssertionAxiom(df.getOWLObjectSomeValuesFrom((OWLObjectProperty) p, (OWLClass) e_to), (OWLNamedIndividual) e_from);
                    } else if (e_to instanceof OWLNamedIndividual) {
                        return df.getOWLObjectPropertyAssertionAxiom((OWLObjectProperty) p, (OWLNamedIndividual) e_from, (OWLNamedIndividual) e_to);
                    } else {
                        log("Not deal with OWLClass-" + type + "-X");
                    }
                } else {
                    log("Not deal with X-" + type + "-X");
                }
            }
            if (p instanceof OWLAnnotationProperty) {
                return df.getOWLAnnotationAssertionAxiom(e_from.getIRI(), df.getOWLAnnotation((OWLAnnotationProperty) p, e_to.getIRI()));
            }
        }
        throw new N2OException("Unknown relationship type: " + type, new NullPointerException());
    }


    private void addAnnotations(OWLOntology o) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        n2OEntityManager.entities().forEach(e -> addAnnotationsForEntity(o, changes, e));
        o.getOWLOntologyManager().applyChanges(changes);
    }

    private void addAnnotationsForEntity(OWLOntology o, List<OWLOntologyChange> changes, OWLEntity e) {
        n2OEntityManager.annotationsProperties(e).forEach(qsl_anno -> addEntityForEntityAndAnnotationProperty(o, changes, e, qsl_anno));

        // Add all neo4jlabels to node
        OWLAnnotationProperty annop = df.getOWLAnnotationProperty(IRI.create(N2OStatic.NEO4J_LABEL));
        n2OEntityManager.nodeLabels(e).forEach(type -> changes.add(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(annop, e.getIRI(), df.getOWLLiteral(type)))));

        // Add entity declarations for all entities
        // TODO this is probably redundant with the initial addEntities(o); call.
        n2OEntityManager.nodeLabels(e).forEach(type -> changes.add(new AddAxiom(o, df.getOWLDeclarationAxiom(e))));
    }

    private void addEntityForEntityAndAnnotationProperty(OWLOntology o, List<OWLOntologyChange> changes, OWLEntity e, String qsl_anno) {
        Object annos = n2OEntityManager.annotationValues(e, qsl_anno);
        OWLAnnotationProperty annoP = getAnnotationProperty(qsl_anno);
        if (annos instanceof Collection) {
            for (Object aa : (Collection) annos) {
                if (annoP == null) {
                    qsls_with_no_matching_properties.add(qsl_anno);
                } else {
                    addAnnotationForEntityAndAnnotationAndValueProperty(o, changes, e, annoP, aa);
                }
            }
        }
    }

    /*
    This method maps neo4j property value to OWL
     */
    private void addAnnotationForEntityAndAnnotationAndValueProperty(OWLOntology o, List<OWLOntologyChange> changes, OWLEntity e, OWLAnnotationProperty annop, Object aa) {
        if (aa.getClass().isArray()) {
            aa = toObjectArray(aa);
            for (Object value : (Object[]) aa) {
                //System.out.println("AVV: " + value.getClass());
                //System.out.println("IRI: " + annop.getIRI());
                changes.add(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(annop, e.getIRI(), getLiteral(value))));
            }
        } else {
            changes.add(new AddAxiom(o, df.getOWLAnnotationAssertionAxiom(annop, e.getIRI(), getLiteral(aa))));
        }
    }

    /*
    From https://stackoverflow.com/a/5608477/2451542
     */
    private Object[] toObjectArray(Object val) {
        if (val instanceof Object[])
            return (Object[]) val;
        int arrlength = Array.getLength(val);
        Object[] outputArray = new Object[arrlength];
        for (int i = 0; i < arrlength; ++i) {
            outputArray[i] = Array.get(val, i);
        }
        return outputArray;
    }

    private OWLAnnotationValue getLiteral(Object value) {
        if (value instanceof Boolean) {
            return df.getOWLLiteral((Boolean) value);
        } else if (value instanceof Integer) {
            return df.getOWLLiteral((Integer) value);
        } else if (value instanceof Float) {
            return df.getOWLLiteral((Float) value);
        } else if (value instanceof Double) {
            return df.getOWLLiteral((Double) value);
        } else {
            return df.getOWLLiteral(value.toString());
        }
    }

    private OWLAnnotationProperty getAnnotationProperty(String qsl_anno) {
        OWLEntity e = n2OEntityManager.getRelationshipByQSL(qsl_anno);
        if (e instanceof OWLAnnotationProperty) {
            return (OWLAnnotationProperty) e;
        }
        //log("Warning: QSL "+qsl_anno+" was not found!");
        return df.getOWLAnnotationProperty(IRI.create(N2OStatic.NEO4J_UNMAPPED_PROPERTY_PREFIX_URI + qsl_anno));
    }

    /*
    For every node labelled "Entity" in the KB, create a corresponding OWL entity, and a declaration in the ontology
    Nothing else is added at this step - just declarations. The main purpose is to index all entities for the next
    Steps in the pipeline
     */
    private void addEntities(OWLOntology o) {
        String cypher = "MATCH (n:Entity) Return n";
        db.execute(cypher).stream().forEach(r -> createEntityForEachLabel((NodeProxy) r.get("n")));
        n2OEntityManager.entities().forEach((e -> addDeclaration(e, o)));
    }

    private void addDeclaration(OWLEntity e, OWLOntology o) {
        o.getOWLOntologyManager().addAxiom(o, df.getOWLDeclarationAxiom(e));
    }

    private void createEntityForEachLabel(NodeProxy n) {
        n.getLabels().forEach(l -> n2OEntityManager.createEntity(n, l.name()));
    }


}
