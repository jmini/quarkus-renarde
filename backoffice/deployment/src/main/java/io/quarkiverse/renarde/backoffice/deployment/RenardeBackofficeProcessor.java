package io.quarkiverse.renarde.backoffice.deployment;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Supplier;

import javax.enterprise.context.RequestScoped;
import javax.persistence.Entity;
import javax.transaction.Transactional;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.resteasy.reactive.MultipartForm;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import io.quarkiverse.renarde.Controller;
import io.quarkiverse.renarde.backoffice.BackUtil;
import io.quarkiverse.renarde.backoffice.CreateAction;
import io.quarkiverse.renarde.backoffice.EditAction;
import io.quarkiverse.renarde.backoffice.deployment.ModelField.Type;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BranchResult;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.FieldCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.deployment.EntityField;
import io.quarkus.panache.common.deployment.EntityModel;
import io.quarkus.panache.common.deployment.HibernateMetamodelForFieldAccessBuildItem;
import io.quarkus.qute.Engine;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.TemplateLocator;
import io.quarkus.qute.Variant;
import io.quarkus.qute.runtime.TemplateProducer;
import io.quarkus.resteasy.reactive.spi.GeneratedJaxRsResourceBuildItem;
import io.quarkus.resteasy.reactive.spi.GeneratedJaxRsResourceGizmoAdaptor;
import io.smallrye.common.annotation.Blocking;

public class RenardeBackofficeProcessor {

    public static final String URI_PREFIX = "/_renarde/backoffice";
    public static final String PACKAGE_PREFIX = "rest._renarde.backoffice";

    public enum Mode {
        EDIT,
        CREATE
    }

    private static final DotName DOTNAME_ENTITY = DotName.createSimple(Entity.class.getName());

    @BuildStep
    public void processModel(HibernateMetamodelForFieldAccessBuildItem metamodel,
            CombinedIndexBuildItem index,
            BuildProducer<GeneratedResourceBuildItem> output,
            BuildProducer<GeneratedJaxRsResourceBuildItem> jaxrsOutput,
            BuildProducer<GeneratedClassBuildItem> classOutput) {
        Engine engine = Engine.builder()
                .addDefaults()
                .addValueResolver(new ReflectionValueResolver())
                .addLocator(new TemplateLocator() {
                    @Override
                    public Optional<TemplateLocation> locate(String id) {
                        URL url = RenardeBackofficeProcessor.class.getClassLoader()
                                .getResource("/templates/" + id);
                        if (url == null) {
                            return Optional.empty();
                        }
                        return Optional.of(new TemplateLocation() {

                            @Override
                            public Reader read() {
                                return new InputStreamReader(
                                        RenardeBackofficeProcessor.class.getClassLoader()
                                                .getResourceAsStream("/templates/" + id),
                                        StandardCharsets.UTF_8);
                            }

                            @Override
                            public Optional<Variant> getVariant() {
                                return Optional.empty();
                            }

                        });
                    }
                }).build();

        generateAllController(jaxrsOutput);

        List<String> entities = new ArrayList<>();
        for (String entity : metamodel.getMetamodelInfo().getEntitiesWithPublicFields()) {
            ClassInfo classInfo = index.getIndex().getClassByName(DotName.createSimple(entity));
            // skip mapped superclasses
            if (classInfo.classAnnotation(DOTNAME_ENTITY) == null)
                continue;
            EntityModel entityModel = metamodel.getMetamodelInfo().getEntityModel(entity);
            String simpleName = entityModel.name;
            int nameLastDot = simpleName.lastIndexOf('.');
            if (nameLastDot != -1)
                simpleName = simpleName.substring(nameLastDot + 1);
            entities.add(simpleName);

            // collect fields
            List<ModelField> fields = new ArrayList<>();
            for (Entry<String, EntityField> entry : entityModel.fields.entrySet()) {
                ModelField mf = new ModelField(entry.getValue(), entity, metamodel.getMetamodelInfo(), index.getIndex());
                if (mf.type != Type.Ignore) {
                    fields.add(mf);
                }
            }

            generateEntityController(entity, simpleName, fields, jaxrsOutput, classOutput);

            TemplateInstance indexTemplate = engine.getTemplate("entity-index.qute").instance();
            indexTemplate.data("entity", simpleName);
            render(output, indexTemplate, simpleName + "/index.html");

            TemplateInstance editTemplate = engine.getTemplate("entity-edit.qute").instance();
            editTemplate.data("entity", simpleName);
            editTemplate.data("fields", fields);
            render(output, editTemplate, simpleName + "/edit.html");

            TemplateInstance createTemplate = engine.getTemplate("entity-create.qute").instance();
            createTemplate.data("entity", simpleName);
            createTemplate.data("fields", fields);
            render(output, createTemplate, simpleName + "/create.html");
        }

        TemplateInstance template = engine.getTemplate("index.qute").instance();
        template.data("entities", entities);
        render(output, template, "index.html");
    }

    private void render(BuildProducer<GeneratedResourceBuildItem> output, TemplateInstance template, String templateId) {
        output.produce(new GeneratedResourceBuildItem("templates/" + URI_PREFIX + "/" + templateId,
                template.render().getBytes(StandardCharsets.UTF_8)));
    }

    private void generateEntityController(String entityClass, String simpleName,
            List<ModelField> fields, BuildProducer<GeneratedJaxRsResourceBuildItem> jaxrsOutput,
            BuildProducer<GeneratedClassBuildItem> classOutput) {
        // TODO: hand it off to RenardeProcessor to generate annotations and uri methods
        // TODO: generate templateinstance native build item or what?
        // TODO: authenticated

        String binaryFieldClassName = null;
        for (ModelField field : fields) {
            if (field.type == Type.Binary) {
                // generate a field class when we find the first binary field
                binaryFieldClassName = generateBinaryFieldClass(fields, simpleName, jaxrsOutput);
                break;
            }
        }

        String controllerClass = PACKAGE_PREFIX + "." + simpleName + "Controller";
        try (ClassCreator c = ClassCreator.builder()
                .classOutput(new GeneratedJaxRsResourceGizmoAdaptor(jaxrsOutput)).className(
                        controllerClass)
                .superClass(Controller.class).build()) {
            c.addAnnotation(Blocking.class.getName());
            c.addAnnotation(RequestScoped.class.getName());
            c.addAnnotation(Path.class).addValue("value", URI_PREFIX + "/" + simpleName);

            try (MethodCreator m = c.getMethodCreator("index", TemplateInstance.class)) {
                m.addAnnotation(Path.class).addValue("value", "index");
                m.addAnnotation(GET.class);
                m.addAnnotation(Produces.class).addValue("value", new String[] { MediaType.TEXT_HTML });

                //              Template template = Arc.container().instance(TemplateProducer.class).get().getInjectableTemplate("_renarde/backoffice/index");
                //              TemplateInstance instance = template.instance();
                //              instance = template.data("entities", Todo.listAll());
                //              return instance;

                ResultHandle entities = m.invokeStaticMethod(MethodDescriptor.ofMethod(entityClass, "listAll", List.class));

                ResultHandle instance = getTemplateInstance(m, URI_PREFIX + "/" + simpleName + "/index");
                instance = m.invokeInterfaceMethod(
                        MethodDescriptor.ofMethod(TemplateInstance.class, "data", TemplateInstance.class, String.class,
                                Object.class),
                        instance, m.load("entities"), entities);
                m.returnValue(instance);
            }

            try (MethodCreator m = c.getMethodCreator("edit", TemplateInstance.class, Long.class)) {
                editOrCreateView(m, controllerClass, entityClass, simpleName, fields, Mode.EDIT);
            }

            editOrCreateAction(c, controllerClass, entityClass, simpleName, fields, binaryFieldClassName, Mode.EDIT);

            try (MethodCreator m = c.getMethodCreator("create", TemplateInstance.class)) {
                editOrCreateView(m, controllerClass, entityClass, simpleName, fields, Mode.CREATE);
            }

            editOrCreateAction(c, controllerClass, entityClass, simpleName, fields, binaryFieldClassName, Mode.CREATE);

            deleteAction(c, controllerClass, entityClass, simpleName);

            addBinaryFieldGetters(c, controllerClass, entityClass, fields);
        }
    }

    /*
     * @GET
     * 
     * @Path("{id}/field")
     * public byte[] fieldForBinary(@RestPath Long id){
     * Entity entity = Entity.findById(id);
     * notFoundIfNull(entity);
     * return BackUtil.readBytes(entity.field);
     * }
     */
    private void addBinaryFieldGetters(ClassCreator c, String controllerClass, String entityClass, List<ModelField> fields) {
        for (ModelField field : fields) {
            if (field.type == Type.Binary) {
                // Add a method to read the binary field data
                try (MethodCreator m = c.getMethodCreator(field.name + "ForBinary", byte[].class, Long.class)) {
                    m.getParameterAnnotations(0).addAnnotation(RestPath.class).addValue("value", "id");
                    m.addAnnotation(Path.class).addValue("value", "{id}/" + field.name);
                    m.addAnnotation(GET.class);
                    AssignableResultHandle entityVariable = findEntityById(m, controllerClass, entityClass);
                    ResultHandle fieldValue = m.readInstanceField(
                            FieldDescriptor.of(entityClass, field.name, field.entityField.descriptor), entityVariable);
                    if (field.entityField.descriptor.equals("[B")) {
                        m.returnValue(fieldValue);
                    } else if (field.entityField.descriptor.equals("L" + Blob.class.getName().replace('.', '/') + ";")) {
                        ResultHandle bytes = m.invokeStaticMethod(
                                MethodDescriptor.ofMethod(BackUtil.class, "readBytes", byte[].class, Blob.class),
                                fieldValue);
                        m.returnValue(bytes);
                    } else {
                        throw new RuntimeException(
                                "Unknown binary field " + field + " descriptor: " + field.entityField.descriptor);
                    }
                }
            }
        }
    }

    //    @POST
    //    @Transactional
    //    @Path("delete/{id}")
    //    public void delete(@RestPath("id") Long id) {
    //        User user = User.findById(id);
    //        notFoundIfNull(user);
    //        user.delete();
    //        flash("message", "Deleted: "+user);
    //        //index();
    //        seeOther("/_renarde/backoffice/index");
    //    }
    private void deleteAction(ClassCreator c, String controllerClass, String entityClass, String simpleName) {
        try (MethodCreator m = c.getMethodCreator("delete", void.class, Long.class)) {
            m.getParameterAnnotations(0).addAnnotation(RestPath.class).addValue("value", "id");
            m.addAnnotation(Path.class).addValue("value", "delete/{id}");
            m.addAnnotation(POST.class);
            m.addAnnotation(Transactional.class);

            AssignableResultHandle variable = findEntityById(m, controllerClass, entityClass);
            m.invokeVirtualMethod(MethodDescriptor.ofMethod(entityClass, "delete", void.class), variable);
            ResultHandle message = m.newInstance(MethodDescriptor.ofConstructor(StringBuilder.class, String.class),
                    m.load("Deleted: "));
            message = m.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(StringBuilder.class, "append", StringBuilder.class, Object.class), message,
                    variable);
            message = m.invokeVirtualMethod(MethodDescriptor.ofMethod(StringBuilder.class, "toString", String.class),
                    message);
            m.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(controllerClass, "flash", void.class, String.class, Object.class),
                    m.getThis(), m.load("message"), message);
            m.invokeVirtualMethod(MethodDescriptor.ofMethod(controllerClass, "seeOther", Response.class, String.class),
                    m.getThis(), m.load(URI_PREFIX + "/" + simpleName + "/index"));
            m.returnValue(null);
        }
    }

    // @Consumes(MediaType.MULTIPART_FORM_DATA)
    // @Path("edit/{id}")
    // @POST
    // @Transactional
    // public void edit(@RestPath("id") Long id,
    //                    @RestForm("action") EditAction action,
    //                    @RestForm("task") String task,
    //                    @RestForm("done") String done,
    //                    @RestForm("doneDate") String doneDate,
    //                    @RestForm("owner") String owner) {
    //   if(validationFailed(){
    //     // edit(id);
    //     seeOther("/_renarde/backoffice/Entity/edit/"+id);
    //   }
    //   Todo todo = Todo.findById(id);
    //   notFoundIfNull(todo);
    //   todo.setTask(BackUtil.stringField(task));
    //   todo.setDone(BackUtil.booleanField(done));
    //   todo.setDoneDate(BackUtil.dateField(doneDate));
    //   todo.setOwner(BackUtil.isSet(owner) ? User.findById(Long.valueOf(owner)) : null);
    //   flash("message", "Updated: "+todo);
    //   if(action == EditAction.Save) {
    //     // index();
    //     seeOther("/_renarde/backoffice/Entity/index");
    //   } else {
    //     // edit(id);
    //     seeOther("/_renarde/backoffice/Entity/edit/"+id);
    //   }
    // }

    // @Consumes(MediaType.MULTIPART_FORM_DATA)
    // @Path("create")
    // @POST
    // @Transactional
    // public void create(@RestForm("action") CreateAction action,
    //                    @RestForm("task") String task,
    //                    @RestForm("done") String done,
    //                    @RestForm("doneDate") String doneDate,
    //                    @RestForm("owner") String owner) {
    //   if(validationFailed(){
    //     // create();
    //     seeOther("/_renarde/backoffice/create");
    //   }
    //   Todo todo = new Todo();
    //   todo.setTask(BackUtil.stringField(task));
    //   todo.setDone(BackUtil.booleanField(done));
    //   todo.setDoneDate(BackUtil.dateField(doneDate));
    //   todo.setOwner(BackUtil.isSet(owner) ? User.findById(Long.valueOf(owner)) : null);
    //   todo.persist();
    //   flash("message", "Created: "+todo);
    //   if(action == CreateAction.Create) {
    //     // index();
    //     seeOther("/_renarde/backoffice/Entity/index");
    //   } else if(action == CreateAction.CreateAndContinueEditing) {
    //     // edit(todo.id);
    //     seeOther("/_renarde/backoffice/Entity/edit/"+todo.id);
    //   } else {
    //     // create();
    //     seeOther("/_renarde/backoffice/Entity/create");
    //   }
    // }
    private void editOrCreateAction(ClassCreator c, String controllerClass, String entityClass, String simpleName,
            List<ModelField> fields, String binaryFieldClassName, Mode mode) {
        int neededParams = 1; // action
        if (mode == Mode.EDIT) {
            neededParams++; // id
        }
        for (ModelField field : fields) {
            if (field.type != Type.Binary) {
                neededParams++; // regular field
            }
        }
        if (binaryFieldClassName != null) {
            neededParams++; // binary field class
        }
        String[] editParams;
        String[] parameterNames;
        int offset = 0;
        StringBuilder signature = new StringBuilder("(");
        editParams = new String[neededParams];
        if (mode == Mode.EDIT) {
            editParams[0] = Long.class.getName();
            editParams[1] = EditAction.class.getName();
            signature.append("Ljava/lang/Long;");
            signature.append("L" + EditAction.class.getName().replace('.', '/') + ";");
            offset = 2;
            parameterNames = new String[editParams.length];
            parameterNames[0] = "id";
            parameterNames[1] = "action";
        } else {
            editParams[0] = CreateAction.class.getName();
            signature.append("L" + CreateAction.class.getName().replace('.', '/') + ";");
            offset = 1;
            parameterNames = new String[editParams.length];
            parameterNames[0] = "action";
        }
        // add the binary field class if required
        if (binaryFieldClassName != null) {
            parameterNames[offset] = "$binaryCollector";
            editParams[offset++] = binaryFieldClassName;
            signature.append("L" + binaryFieldClassName.replace('.', '/') + ";");
        }
        int i = 0;
        for (ModelField field : fields) {
            // skip binary fields and don't increment
            if (field.type == Type.Binary) {
                continue;
            }
            if (field.type == ModelField.Type.MultiRelation
                    || field.type == ModelField.Type.MultiMultiRelation) {
                editParams[i + offset] = List.class.getName();
                signature.append("Ljava/util/List<Ljava/lang/String;>;");
            } else {
                editParams[i + offset] = String.class.getName();
                signature.append("Ljava/lang/String;");
            }
            // only increment on non-binary fields
            i++;
        }
        signature.append(")V");
        try (MethodCreator m = c.getMethodCreator(mode == Mode.CREATE ? "create" : "edit", void.class, editParams)) {
            m.setSignature(signature.toString());
            if (mode == Mode.EDIT) {
                m.getParameterAnnotations(0).addAnnotation(RestPath.class).addValue("value", "id");
                m.getParameterAnnotations(1).addAnnotation(RestForm.class).addValue("value", "action");
            } else {
                m.getParameterAnnotations(0).addAnnotation(RestForm.class).addValue("value", "action");
            }
            if (binaryFieldClassName != null) {
                m.getParameterAnnotations(offset - 1).addAnnotation(MultipartForm.class);
            }
            i = 0;
            for (ModelField field : fields) {
                // skip binary fields, don't increment
                if (field.type == Type.Binary) {
                    continue;
                }
                m.getParameterAnnotations(i + offset).addAnnotation(RestForm.class).addValue("value", field.name);
                for (Class<? extends Annotation> validationAnnotation : field.validation) {
                    m.getParameterAnnotations(i + offset).addAnnotation(validationAnnotation);
                }
                parameterNames[i + offset] = field.name;
                // increment for non-binary field
                i++;
            }
            m.setParameterNames(parameterNames);
            m.addAnnotation(Consumes.class).addValue("value", new String[] { MediaType.MULTIPART_FORM_DATA });
            m.addAnnotation(Path.class).addValue("value", mode == Mode.CREATE ? "create" : "edit/{id}");
            m.addAnnotation(POST.class);
            m.addAnnotation(Transactional.class);

            String uriTarget = URI_PREFIX + "/" + simpleName + (mode == Mode.CREATE ? "/create" : "/edit/");

            // first check validation

            BranchResult validation = m.ifTrue(m.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(controllerClass, "validationFailed", boolean.class), m.getThis()));
            try (BytecodeCreator tb = validation.trueBranch()) {
                redirectToAction(tb, controllerClass, uriTarget, simpleName,
                        mode == Mode.CREATE ? null : () -> tb.getMethodParam(0));
            }

            AssignableResultHandle entityVariable;
            if (mode == Mode.EDIT) {
                entityVariable = findEntityById(m, controllerClass, entityClass);
            } else {
                String entityTypeDescriptor = "L" + entityClass.replace('.', '/') + ";";
                entityVariable = m.createVariable(entityTypeDescriptor);
                m.assign(entityVariable, m.newInstance(MethodDescriptor.ofConstructor(entityClass)));
            }
            i = 0;
            for (ModelField field : fields) {
                ResultHandle value = null;
                ResultHandle parameterValue;
                if (field.type == Type.Binary) {
                    // get the binary field param
                    parameterValue = m.getMethodParam(offset - 1);
                    // get the right field
                    parameterValue = m.readInstanceField(FieldDescriptor.of(binaryFieldClassName, field.name, FileUpload.class),
                            parameterValue);
                } else {
                    parameterValue = m.getMethodParam(i + offset);
                    i++;
                }
                if (field.type == Type.Text || field.type == Type.LargeText) {
                    if (field.entityField.descriptor.equals("Ljava/lang/String;")) {
                        value = m.invokeStaticMethod(
                                MethodDescriptor.ofMethod(BackUtil.class, "stringField", String.class, String.class),
                                parameterValue);
                    } else if (field.entityField.descriptor.equals("C")) {
                        value = m.invokeStaticMethod(
                                MethodDescriptor.ofMethod(BackUtil.class, "charField", char.class, String.class),
                                parameterValue);
                    } else {
                        throw new RuntimeException(
                                "Unknown text field " + field + " descriptor: " + field.entityField.descriptor);
                    }
                } else if (field.type == Type.Binary) {
                    if (field.entityField.descriptor.equals("[B")) {
                        value = m.invokeStaticMethod(
                                MethodDescriptor.ofMethod(BackUtil.class, "byteArrayField", byte[].class, FileUpload.class),
                                parameterValue);
                    } else if (field.entityField.descriptor.equals("L" + Blob.class.getName().replace('.', '/') + ";")) {
                        value = m.invokeStaticMethod(
                                MethodDescriptor.ofMethod(BackUtil.class, "blobField", Blob.class, FileUpload.class),
                                parameterValue);
                    } else {
                        throw new RuntimeException(
                                "Unknown binary field " + field + " descriptor: " + field.entityField.descriptor);
                    }
                } else if (field.entityField.descriptor.equals("Z")) {
                    value = m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, "booleanField", boolean.class, String.class),
                            parameterValue);
                } else if (field.type == Type.Number) {
                    Class<?> primitiveClass;
                    switch (field.entityField.descriptor) {
                        case "B":
                            primitiveClass = byte.class;
                            break;
                        case "S":
                            primitiveClass = short.class;
                            break;
                        case "I":
                            primitiveClass = int.class;
                            break;
                        case "J":
                            primitiveClass = long.class;
                            break;
                        case "F":
                            primitiveClass = float.class;
                            break;
                        case "D":
                            primitiveClass = double.class;
                            break;
                        default:
                            throw new RuntimeException(
                                    "Unknown number field " + field + " descriptor: " + field.entityField.descriptor);
                    }
                    value = m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, primitiveClass.getName() + "Field", primitiveClass,
                                    String.class),
                            parameterValue);
                } else if (field.entityField.descriptor.equals("Ljava/util/Date;")) {
                    value = m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, "dateField", Date.class, String.class),
                            parameterValue);
                } else if (field.entityField.descriptor.equals("Ljava/time/LocalDateTime;")) {
                    value = m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, "localDateTimeField", LocalDateTime.class, String.class),
                            parameterValue);
                } else if (field.entityField.descriptor.equals("Ljava/time/LocalDate;")) {
                    value = m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, "localDateField", LocalDate.class, String.class),
                            parameterValue);
                } else if (field.entityField.descriptor.equals("Ljava/time/LocalTime;")) {
                    value = m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, "localTimeField", LocalTime.class, String.class),
                            parameterValue);
                } else if (field.type == ModelField.Type.Enum) {
                    value = m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, "enumField", Enum.class, Class.class, String.class),
                            m.loadClass(field.getClassName()),
                            parameterValue);
                    value = m.checkCast(value, field.entityField.descriptor);
                } else if (field.type == ModelField.Type.MultiRelation
                        || field.type == ModelField.Type.MultiMultiRelation) {
                    // This one does not set the value, and does not go via the setter below, it calls the setter itself
                    AssignableResultHandle iterator = m.createVariable(Iterator.class);
                    if (mode == Mode.EDIT) {
                        // // clear previous list
                        // Iterator it = entity.relation.iterator();
                        // while (it.hasNext()) {
                        //     @OneToMany: ((RelationType)it.next()).owningField = null;
                        //     @ManyToMany: ((RelationType)it.next()).owningField.remove(entity);
                        // }
                        ResultHandle relation = m.invokeVirtualMethod(
                                MethodDescriptor.ofMethod(entityClass, field.entityField.getGetterName(),
                                        field.entityField.descriptor),
                                entityVariable);
                        m.assign(iterator, m.invokeInterfaceMethod(
                                MethodDescriptor.ofMethod(Iterable.class, "iterator", Iterator.class), relation));
                        try (BytecodeCreator loop = m
                                .whileLoop(bc -> bc.ifTrue(bc.invokeInterfaceMethod(
                                        MethodDescriptor.ofMethod(Iterator.class, "hasNext", boolean.class), iterator)))
                                .block()) {
                            ResultHandle next = loop.checkCast(
                                    loop.invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterator.class, "next", Object.class),
                                            iterator),
                                    field.relationClass);
                            EntityField inverseField = field.inverseField;
                            if (field.type == ModelField.Type.MultiMultiRelation) {
                                ResultHandle inverseRelation = loop.invokeVirtualMethod(
                                        MethodDescriptor.ofMethod(field.relationClass, inverseField.getGetterName(),
                                                inverseField.descriptor),
                                        next);
                                loop.invokeInterfaceMethod(
                                        MethodDescriptor.ofMethod(List.class, "remove", boolean.class, Object.class),
                                        inverseRelation, entityVariable);
                            } else {
                                loop.invokeVirtualMethod(
                                        MethodDescriptor.ofMethod(field.relationClass, inverseField.getSetterName(), void.class,
                                                inverseField.descriptor),
                                        next, loop.loadNull());
                            }
                        }
                        // entity.relation.clear();
                        relation = m.invokeVirtualMethod(
                                MethodDescriptor.ofMethod(entityClass, field.entityField.getGetterName(),
                                        field.entityField.descriptor),
                                entityVariable);
                        m.invokeInterfaceMethod(MethodDescriptor.ofMethod(List.class, "clear", void.class), relation);
                    } else {
                        // create the empty list and assign it
                        m.invokeVirtualMethod(
                                MethodDescriptor.ofMethod(entityClass, field.entityField.getSetterName(), void.class,
                                        field.entityField.descriptor),
                                entityVariable,
                                m.newInstance(MethodDescriptor.ofConstructor(ArrayList.class)));

                    }
                    // // change new list
                    // Iterator it = value.iterator();
                    // while (it.hasNext()) {
                    //     RelationType relation = RelationType.findById(Long.valueOf((String)it.next()));
                    //     @OneToMany: relation.owningField = entity;
                    //     @ManyToMany: relation.owningField.add(entity);
                    //     entity.relation.add(relation);
                    // }
                    m.assign(iterator,
                            m.invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterable.class, "iterator", Iterator.class),
                                    parameterValue));
                    try (BytecodeCreator loop = m.whileLoop(bc -> bc.ifTrue(bc.invokeInterfaceMethod(
                            MethodDescriptor.ofMethod(Iterator.class, "hasNext", boolean.class), iterator))).block()) {
                        ResultHandle next = loop.checkCast(
                                loop.invokeInterfaceMethod(MethodDescriptor.ofMethod(Iterator.class, "next", Object.class),
                                        iterator),
                                String.class);
                        EntityField inverseField = field.inverseField;
                        String relationSignature = "L" + field.relationClass.replace('.', '/') + ";";
                        AssignableResultHandle otherEntityVar = m.createVariable(relationSignature);
                        ResultHandle id = loop.invokeStaticMethod(
                                MethodDescriptor.ofMethod(Long.class, "valueOf", Long.class, String.class),
                                next);
                        ResultHandle otherEntity = loop.invokeStaticMethod(
                                MethodDescriptor.ofMethod(field.relationClass, "findById", PanacheEntityBase.class,
                                        Object.class),
                                id);
                        otherEntity = loop.checkCast(otherEntity, field.relationClass);
                        loop.assign(otherEntityVar, otherEntity);

                        if (field.type == ModelField.Type.MultiMultiRelation) {
                            ResultHandle inverseRelation = loop.invokeVirtualMethod(
                                    MethodDescriptor.ofMethod(field.relationClass, inverseField.getGetterName(),
                                            inverseField.descriptor),
                                    otherEntityVar);
                            loop.invokeInterfaceMethod(
                                    MethodDescriptor.ofMethod(List.class, "add", boolean.class, Object.class),
                                    inverseRelation, entityVariable);
                        } else {
                            loop.invokeVirtualMethod(
                                    MethodDescriptor.ofMethod(field.relationClass, inverseField.getSetterName(), void.class,
                                            inverseField.descriptor),
                                    otherEntityVar, entityVariable);
                        }
                        ResultHandle relation = loop.invokeVirtualMethod(
                                MethodDescriptor.ofMethod(entityClass, field.entityField.getGetterName(),
                                        field.entityField.descriptor),
                                entityVariable);
                        loop.invokeInterfaceMethod(MethodDescriptor.ofMethod(List.class, "add", boolean.class, Object.class),
                                relation, otherEntityVar);
                    }
                } else if (field.type == ModelField.Type.Ignore) {
                    continue;
                } else if (field.type == ModelField.Type.Relation) {
                    BranchResult branch = m.ifTrue(m.invokeStaticMethod(
                            MethodDescriptor.ofMethod(BackUtil.class, "isSet", boolean.class, String.class),
                            parameterValue));
                    AssignableResultHandle valueVar = m.createVariable(field.entityField.descriptor);
                    try (BytecodeCreator tb = branch.trueBranch()) {
                        value = tb.invokeStaticMethod(
                                MethodDescriptor.ofMethod(Long.class, "valueOf", Long.class, String.class),
                                parameterValue);
                        value = tb.invokeStaticMethod(
                                MethodDescriptor.ofMethod(field.getClassName(), "findById", PanacheEntityBase.class,
                                        Object.class),
                                value);
                        value = tb.checkCast(value, field.entityField.descriptor);
                        tb.assign(valueVar, value);
                    }
                    try (BytecodeCreator fb = branch.falseBranch()) {
                        fb.assign(valueVar, fb.loadNull());
                    }
                    value = valueVar;
                } else {
                    throw new RuntimeException("Don't know what to do with field of type " + field.entityField.descriptor
                            + " from field " + simpleName + "." + field.name);
                }
                // FIXME: temporary
                if (value != null)
                    m.invokeVirtualMethod(MethodDescriptor.ofMethod(entityClass, field.entityField.getSetterName(), void.class,
                            field.entityField.descriptor), entityVariable, value);
            }
            if (mode == Mode.CREATE) {
                m.invokeVirtualMethod(MethodDescriptor.ofMethod(entityClass, "persist", void.class), entityVariable);
            }
            // flash message
            ResultHandle message = m.newInstance(MethodDescriptor.ofConstructor(StringBuilder.class, String.class),
                    m.load(mode == Mode.CREATE ? "Created: " : "Updated: "));
            message = m.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(StringBuilder.class, "append", StringBuilder.class, Object.class), message,
                    entityVariable);
            message = m.invokeVirtualMethod(MethodDescriptor.ofMethod(StringBuilder.class, "toString", String.class),
                    message);
            m.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(controllerClass, "flash", void.class, String.class, Object.class),
                    m.getThis(), m.load("message"), message);
            // final redirect
            if (mode == Mode.EDIT) {
                BranchResult ifSave = m.ifReferencesEqual(m.getMethodParam(1),
                        m.readStaticField(FieldDescriptor.of(EditAction.class, "Save", EditAction.class)));
                try (BytecodeCreator tb = ifSave.trueBranch()) {
                    String indexTarget = URI_PREFIX + "/" + simpleName + "/index";
                    redirectToAction(tb, controllerClass, indexTarget, simpleName, null);
                }
                try (BytecodeCreator fb = ifSave.falseBranch()) {
                    String editTarget = URI_PREFIX + "/" + simpleName + "/edit/";
                    redirectToAction(fb, controllerClass, editTarget, simpleName, () -> fb.getMethodParam(0));
                }
            } else {
                BranchResult ifCreate = m.ifReferencesEqual(m.getMethodParam(0),
                        m.readStaticField(FieldDescriptor.of(CreateAction.class, "Create", CreateAction.class)));
                try (BytecodeCreator tb = ifCreate.trueBranch()) {
                    String indexTarget = URI_PREFIX + "/" + simpleName + "/index";
                    redirectToAction(tb, controllerClass, indexTarget, simpleName, null);
                }
                try (BytecodeCreator fb = ifCreate.falseBranch()) {
                    BranchResult ifCreateAndContinueEditing = fb.ifReferencesEqual(fb.getMethodParam(0),
                            fb.readStaticField(
                                    FieldDescriptor.of(CreateAction.class, "CreateAndContinueEditing", CreateAction.class)));
                    try (BytecodeCreator tb2 = ifCreateAndContinueEditing.trueBranch()) {
                        String editTarget = URI_PREFIX + "/" + simpleName + "/edit/";
                        redirectToAction(tb2, controllerClass, editTarget, simpleName,
                                () -> tb2.invokeVirtualMethod(MethodDescriptor.ofMethod(entityClass, "getId", Long.class),
                                        entityVariable));
                    }
                    try (BytecodeCreator fb2 = ifCreate.falseBranch()) {
                        String createTarget = URI_PREFIX + "/" + simpleName + "/create";
                        redirectToAction(fb2, controllerClass, createTarget, simpleName, null);
                    }
                }
            }
            m.returnValue(null);
        }
    }

    private String generateBinaryFieldClass(List<ModelField> binaryFields,
            String simpleName,
            BuildProducer<GeneratedJaxRsResourceBuildItem> classOutput) {
        String binaryFieldsClass = PACKAGE_PREFIX + "." + simpleName + "ControllerBinaryFields";
        try (ClassCreator c = ClassCreator.builder()
                .classOutput(new GeneratedJaxRsResourceGizmoAdaptor(classOutput)).className(
                        binaryFieldsClass)
                .build()) {
            for (ModelField field : binaryFields) {
                FieldCreator fc = c.getFieldCreator(FieldDescriptor.of(binaryFieldsClass, field.name, FileUpload.class));
                fc.addAnnotation(RestForm.class);
                fc.setModifiers(Modifier.PUBLIC);
            }
        }
        return binaryFieldsClass;
    }

    private void redirectToAction(BytecodeCreator m, String controllerClass, String uriTarget, String simpleName,
            Supplier<ResultHandle> getId) {
        ResultHandle uri;
        if (getId != null) {
            uri = m.newInstance(MethodDescriptor.ofConstructor(StringBuilder.class, String.class),
                    m.load(uriTarget));
            uri = m.invokeVirtualMethod(
                    MethodDescriptor.ofMethod(StringBuilder.class, "append", StringBuilder.class, Object.class), uri,
                    getId.get());
            uri = m.invokeVirtualMethod(MethodDescriptor.ofMethod(StringBuilder.class, "toString", String.class), uri);
        } else {
            uri = m.load(uriTarget);
        }
        m.invokeVirtualMethod(MethodDescriptor.ofMethod(controllerClass, "seeOther", Response.class, String.class),
                m.getThis(), uri);
    }

    // @GET
    // @Produces(html)
    // @Path("edit/{id}")
    // public TemplateInstance edit(@RestPath("id") Long id) {
    //   Todo todo = Todo.findById(id);
    //   notFoundIfNull(todo);
    //   return Templates.edit(todo, BackUtil.entityValues(User.listAll()));
    // }

    // @GET
    // @Produces(html)
    // @Path("create")
    // public TemplateInstance create() {
    //   return Templates.create(BackUtil.entityValues(User.listAll()));
    // }
    private void editOrCreateView(MethodCreator m, String controllerClass, String entityClass, String simpleName,
            List<ModelField> fields, Mode mode) {
        if (mode == Mode.EDIT) {
            m.getParameterAnnotations(0).addAnnotation(RestPath.class).addValue("value", "id");
            m.addAnnotation(Path.class).addValue("value", "edit/{id}");
        } else {
            m.addAnnotation(Path.class).addValue("value", "create");
        }
        m.addAnnotation(GET.class);
        m.addAnnotation(Produces.class).addValue("value", new String[] { MediaType.TEXT_HTML });

        ResultHandle instance = getTemplateInstance(m,
                URI_PREFIX + "/" + simpleName + "/" + (mode == Mode.CREATE ? "create" : "edit"));
        AssignableResultHandle entityVariable = null;
        if (mode == Mode.EDIT) {
            entityVariable = findEntityById(m, controllerClass, entityClass);

            instance = m.invokeInterfaceMethod(
                    MethodDescriptor.ofMethod(TemplateInstance.class, "data", TemplateInstance.class, String.class,
                            Object.class),
                    instance, m.load("entity"), entityVariable);
        }

        for (ModelField field : fields) {
            ResultHandle data = null;
            if (field.type == ModelField.Type.Relation
                    || field.type == ModelField.Type.MultiRelation
                    || field.type == ModelField.Type.MultiMultiRelation) {
                ResultHandle list = m
                        .invokeStaticMethod(MethodDescriptor.ofMethod(field.relationClass, "listAll", List.class));
                data = m.invokeStaticMethod(
                        MethodDescriptor.ofMethod(BackUtil.class, "entityPossibleValues", Map.class, List.class), list);
            } else if (field.type == ModelField.Type.Enum) {
                ResultHandle list = m
                        .invokeStaticMethod(
                                MethodDescriptor.ofMethod(field.getClassName(), "values",
                                        "[" + field.entityField.descriptor));
                data = m.invokeStaticMethod(
                        MethodDescriptor.ofMethod(BackUtil.class, "enumPossibleValues", Map.class, Enum[].class), list);
            }
            if (data != null) {
                instance = m.invokeInterfaceMethod(
                        MethodDescriptor.ofMethod(TemplateInstance.class, "data", TemplateInstance.class, String.class,
                                Object.class),
                        instance, m.load(field.name + "PossibleValues"), data);
            }
            if (mode == Mode.EDIT
                    && (field.type == ModelField.Type.MultiRelation || field.type == ModelField.Type.MultiMultiRelation)) {
                // instance.data("relationCurrentValues", BackUtil.entityCurrentValues(entity.getRelation()))
                ResultHandle relation = m.invokeVirtualMethod(
                        MethodDescriptor.ofMethod(entityClass, field.entityField.getGetterName(),
                                field.entityField.descriptor),
                        entityVariable);
                data = m.invokeStaticMethod(
                        MethodDescriptor.ofMethod(BackUtil.class, "entityCurrentValues", List.class, List.class), relation);
                instance = m.invokeInterfaceMethod(
                        MethodDescriptor.ofMethod(TemplateInstance.class, "data", TemplateInstance.class, String.class,
                                Object.class),
                        instance, m.load(field.name + "CurrentValues"), data);
            }
        }
        m.returnValue(instance);
    }

    private AssignableResultHandle findEntityById(MethodCreator m, String controllerClass, String entityClass) {
        ResultHandle entity = m.invokeStaticMethod(
                MethodDescriptor.ofMethod(entityClass, "findById", PanacheEntityBase.class, Object.class),
                m.getMethodParam(0));
        String entityTypeDescriptor = "L" + entityClass.replace('.', '/') + ";";
        AssignableResultHandle variable = m.createVariable(entityTypeDescriptor);
        m.assign(variable, m.checkCast(entity, entityTypeDescriptor));

        m.invokeVirtualMethod(MethodDescriptor.ofMethod(controllerClass, "notFoundIfNull", void.class, Object.class),
                m.getThis(), variable);
        return variable;
    }

    private ResultHandle getTemplateInstance(MethodCreator m, String templateName) {
        ResultHandle container = m
                .invokeStaticMethod(MethodDescriptor.ofMethod(Arc.class, "container", ArcContainer.class));
        ResultHandle instanceHandle = m.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(ArcContainer.class, "instance", InstanceHandle.class, Class.class,
                        Annotation[].class),
                container, m.loadClass(TemplateProducer.class), m.newArray(Annotation.class, 0));
        ResultHandle templateProducer = m
                .checkCast(m.invokeInterfaceMethod(MethodDescriptor.ofMethod(InstanceHandle.class, "get", Object.class),
                        instanceHandle), TemplateProducer.class);
        ResultHandle template = m.invokeVirtualMethod(MethodDescriptor.ofMethod(TemplateProducer.class,
                "getInjectableTemplate", Template.class, String.class), templateProducer, m.load(templateName));
        return m.invokeInterfaceMethod(
                MethodDescriptor.ofMethod(Template.class, "instance", TemplateInstance.class), template);
    }

    private void generateAllController(BuildProducer<GeneratedJaxRsResourceBuildItem> jaxrsOutput) {
        // TODO: hand it off to RenardeProcessor to generate annotations and uri methods
        // TODO: generate templateinstance native build item or what?
        try (ClassCreator c = ClassCreator.builder()
                .classOutput(new GeneratedJaxRsResourceGizmoAdaptor(jaxrsOutput)).className(
                        PACKAGE_PREFIX + ".Index")
                .superClass(Controller.class).build()) {
            c.addAnnotation(Blocking.class.getName());
            c.addAnnotation(RequestScoped.class.getName());
            c.addAnnotation(Path.class).addValue("value", URI_PREFIX);

            try (MethodCreator m = c.getMethodCreator("index", TemplateInstance.class)) {
                m.addAnnotation(Path.class).addValue("value", "index");
                m.addAnnotation(GET.class);
                m.addAnnotation(Produces.class).addValue("value", new String[] { MediaType.TEXT_HTML });

                //              Template template = Arc.container().instance(TemplateProducer.class).get().getInjectableTemplate("Back/index");
                //              TemplateInstance instance = template.instance();
                //              return instance;

                ResultHandle instance = getTemplateInstance(m, URI_PREFIX + "/index");
                m.returnValue(instance);
            }
        }
    }
}
