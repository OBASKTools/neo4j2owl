package ebi.spot.neo4j2owl;

import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.HashSet;
import java.util.Set;

public class N2OEntity {
    private final String ns;
    private final String iri;
    private final String safe_label;
    private final String qualified_safe_label;
    private final String label;
    private final Set<String> types;
    private final String short_form;
    private final String curie;
    private final OWLEntity entity;
    private final long id;


    N2OEntity(OWLEntity e, OWLOntology o, IRIManager curies, long id) {
        iri = e.getIRI().toString();
        safe_label = curies.getSafeLabel(e,o);
        label = curies.getLabel(e,o);
        types = new HashSet<>();
        types.add(OWL2NeoMapping.getNeoType(e));
        ns = curies.getNamespace(e.getIRI());
        qualified_safe_label = curies.getQualifiedSafeLabel(e,o);
        short_form = curies.getShortForm(e.getIRI());
        curie = curies.getCurie(e);
        entity = e;
        this.id = id;
    }


    public String getIri() {
        return iri;
    }

    public String getSafe_label() {
        return safe_label;
    }

    public String getQualified_safe_label() {
        return qualified_safe_label;
    }

    public String getShort_form() {
        return short_form;
    }

    public String getCurie() {
        return curie;
    }

    public String getLabel() {
        return label;
    }

    public Set<String> getTypes() {
        return types;
    }

    @Override
    public String toString() {
        return "N2OEntity{" +
                "ns='" + ns + '\'' +
                ", iri='" + iri + '\'' +
                ", safe_label='" + safe_label + '\'' +
                ", qualified_safe_label='" + qualified_safe_label + '\'' +
                ", label='" + label + '\'' +
                ", type='" + types + '\'' +
                ", short_form='" + short_form + '\'' +
                ", curie='" + curie + '\'' +
                '}';
    }

    public OWLEntity getEntity() {
        return entity;
    }

    public long getId() {
        return id;
    }

    public String getEntityType() {
        if (getEntity() instanceof OWLAnnotationProperty) {
            return "Annotation";
        } else if (getEntity() instanceof OWLObjectProperty) {
            return "Related";
        }
        return "";
    }

    public void addLabels(Set<String> labels) {
        types.addAll(labels);
    }
}
