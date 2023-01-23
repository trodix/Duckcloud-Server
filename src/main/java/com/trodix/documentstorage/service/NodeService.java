package com.trodix.documentstorage.service;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.trodix.documentstorage.entity.Aspect;
import com.trodix.documentstorage.entity.Namespace;
import com.trodix.documentstorage.entity.Node;
import com.trodix.documentstorage.entity.Property;
import com.trodix.documentstorage.entity.QName;
import com.trodix.documentstorage.model.NodeRepresentation;
import com.trodix.documentstorage.repository.AspectRepository;
import com.trodix.documentstorage.repository.NamespaceRepository;
import com.trodix.documentstorage.repository.NodeRepository;
import com.trodix.documentstorage.repository.QNameRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@AllArgsConstructor
@Slf4j
public class NodeService {

    private final StorageService storageService;

    private final NodeRepository nodeRepository;

    private final NamespaceRepository namespaceRepository;

    private final QNameRepository qnameRepository;

    private final AspectRepository aspectRepository;

    public Node nodeRepresentationToNode(final NodeRepresentation nodeRepresentation) {

        final List<Aspect> aspects = new ArrayList<>();

        nodeRepresentation.getAspects().stream().forEach(aspectString -> {

            QName qnameAspect = stringToQName(aspectString);

            final Optional<Aspect> resultAspect = aspectRepository.findOneByQname(qnameAspect);

            final Aspect aspect;

            if (resultAspect.isEmpty()) {
                aspect = new Aspect();
                aspect.setQname(qnameAspect);
            } else {
                aspect = resultAspect.get();
            }

            aspects.add(aspect);
        });

        final List<Property> properties = new ArrayList<>();

        nodeRepresentation.getProperties().forEach((key, value) -> {
            final Property property = new Property();
            property.setQname(stringToQName(key));
            property.setJavaType(value.getClass().getCanonicalName());
            property.setValue(value);

            properties.add(property);
        });

        final Node node = new Node();
        node.setBucket(nodeRepresentation.getBucket());
        node.setDirectoryPath(nodeRepresentation.getDirectoryPath());
        node.setUuid(StringUtils.isBlank(nodeRepresentation.getUuid()) ? UUID.randomUUID().toString() : nodeRepresentation.getUuid());
        node.setAspects(aspects);
        node.setProperties(properties);

        return node;
    }

    public NodeRepresentation nodeToNodeRepresentation(final Node node) {

        final List<String> aspects = new ArrayList<>();

        node.getAspects().stream().forEach(aspect -> {
            final String aspectString = qnameToString(aspect.getQname());
            aspects.add(aspectString);
        });

        final Map<String, Serializable> properties = new HashMap<>();

        node.getProperties().stream().forEach(property -> {
            final String propertyName = qnameToString(property.getQname());
            final Serializable propertyValue = property.getValue();

            properties.put(propertyName, propertyValue);
        });

        final NodeRepresentation nodeRepresentation = new NodeRepresentation();
        nodeRepresentation.setBucket(node.getBucket());
        nodeRepresentation.setDirectoryPath(node.getDirectoryPath());
        nodeRepresentation.setUuid(node.getUuid());
        nodeRepresentation.setAspects(null);
        nodeRepresentation.setProperties(null);

        return nodeRepresentation;
    }

    public QName stringToQName(final String qnameString) {
        final String[] parts = qnameString.split(":");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid QName representation: " + qnameString);
        }

        final Optional<Namespace> resultNamespace = namespaceRepository.findOneByName(parts[0]);

        final Namespace namespace;

        if (resultNamespace.isEmpty()) {
            namespace = new Namespace();
            namespace.setName(parts[0]);
        } else {
            namespace = resultNamespace.get();
        }

        Optional<QName> resultQname = Optional.empty();

        if (namespace.getId() != null) {
            resultQname = qnameRepository.findOneByNamespaceIdAndName(namespace.getId(), parts[1]);
        }

        QName qname;

        if (resultQname.isEmpty()) {
            qname = new QName();
            qname.setNamespace(namespace);
            qname.setName(parts[1]);
            qname = qnameRepository.save(qname);
        } else {
            qname = resultQname.get();
        }

        return qname;
    }

    public String qnameToString(final QName qname) {
        return qname.getNamespace() + ":" + qname.getName();
    }

    public NodeRepresentation persistNode(final NodeRepresentation nodeRep, final byte[] file) {
        final Node node = nodeRepresentationToNode(nodeRep);
        // TODO support multiple buckets
        node.setBucket(StorageService.ROOT_BUCKET);
        nodeRepository.save(node);

        nodeRep.setUuid(node.getUuid());
        nodeRep.setBucket(node.getBucket());

        storageService.uploadFile(nodeRep, file);

        log.debug("File uploaded: {}", nodeRep);

        return nodeRep;
    }

}