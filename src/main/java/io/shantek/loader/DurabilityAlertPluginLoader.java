package io.shantek.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

public class DurabilityAlertPluginLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder("maven-central", "default", "https://repo.maven.apache.org/maven2/").build());
        resolver.addDependency(dependency("com.zaxxer:HikariCP:7.0.2"));
        resolver.addDependency(dependency("org.xerial:sqlite-jdbc:3.50.3.0"));
        resolver.addDependency(dependency("com.github.ben-manes.caffeine:caffeine:3.2.3"));
        classpathBuilder.addLibrary(resolver);
    }

    private Dependency dependency(String coordinates) {
        return new Dependency(new DefaultArtifact(coordinates), null);
    }
}
