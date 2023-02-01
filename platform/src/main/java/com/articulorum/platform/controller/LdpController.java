package com.articulorum.platform.controller;

import static com.articulorum.platform.utility.PathUtility.SLASH;
import static com.articulorum.platform.utility.PathUtility.getParentPath;
import static com.articulorum.platform.utility.PathUtility.getPath;
import static java.lang.String.format;
import static java.lang.String.join;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.removeEnd;
import static org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentRequest;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import javax.persistence.EntityExistsException;
import javax.persistence.EntityNotFoundException;

import com.articulorum.domain.Container;
import com.articulorum.platform.service.ContainerService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponents;

@RestController
public class LdpController {

    public final static String ROOT = SLASH;
    public final static String ANT_PATH = "/**/*";

    public final static String NOT_FOUND_TEMPLATE = "Container with path %s was not found";
    public final static String PARENT_NOT_FOUND_TEMPLATE = "Container parent with path %s was not found";
    public final static String ALREADY_EXISTS_TEMPLATE = "Container with path %s already exists";

    @Autowired
    private ContainerService containerService;

    @ResponseStatus(value = HttpStatus.OK)
    @GetMapping(value = { ROOT, ANT_PATH })
    public @ResponseBody Container get() throws IOException {
        final UriComponents uriComponents = fromCurrentRequest().build();
        final String path = getPath(uriComponents);
        final Optional<Container> container = containerService.findByPath(path);
        if (!container.isPresent()) {
            throw new EntityNotFoundException(format(NOT_FOUND_TEMPLATE, path));
        }
        return container.get();
    }

    @Transactional
    @PostMapping(value = { ROOT, ANT_PATH })
    @ResponseStatus(value = HttpStatus.CREATED)
    public String post(@RequestBody Container container, @RequestHeader(required = false) Optional<String> slug) {
        final UriComponents uriComponents = fromCurrentRequest().build();
        final UUID id = UUID.randomUUID();
        final String basePath = getPath(uriComponents);
        final String currentPath = slug.isPresent() ? slug.get() : id.toString();
        final String path = isEmpty(basePath) ? currentPath : join(SLASH, basePath, currentPath);
        if (containerService.existsByPath(path)) {
            throw new EntityExistsException(format(ALREADY_EXISTS_TEMPLATE, path));
        }

        final String baseUri = uriComponents.toUriString();
        final String uri = join(SLASH, removeEnd(baseUri, SLASH), currentPath);

        Optional<Container> parent = containerService.findByPath(basePath);

        if (!parent.isPresent()) {
            throw new EntityNotFoundException(format(NOT_FOUND_TEMPLATE, basePath));    
        }

        container.setId(id);
        container.setPath(path);

        return containerService.create(parent.get(), container, uri);
    }

    // TODO: implement PUT and PATCH

    @Transactional
    @DeleteMapping(value = { ANT_PATH })
    @ResponseStatus(value = HttpStatus.NO_CONTENT)
    public @ResponseBody void delete() throws IOException {
        final UriComponents uriComponents = fromCurrentRequest().build();
        final String path = getPath(uriComponents);
        final String uri = uriComponents.toUriString();
        final Optional<Container> container = containerService.findByPath(path);

        if (!container.isPresent()) {
            throw new EntityNotFoundException(format(NOT_FOUND_TEMPLATE, path));
        }

        final String parentPath = getParentPath(uriComponents);
        final Optional<Container> parent = containerService.findByPath(parentPath);

        if (!parent.isPresent()) {
            throw new EntityNotFoundException(format(PARENT_NOT_FOUND_TEMPLATE, parentPath));
        }

        containerService.delete(parent.get(), container.get(), uri);
    }

}
