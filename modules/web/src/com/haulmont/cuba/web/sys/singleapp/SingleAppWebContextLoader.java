/*
 * Copyright (c) 2008-2016 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.haulmont.cuba.web.sys.singleapp;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.haulmont.bali.util.ReflectionHelper;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.CubaClassPathXmlApplicationContext;
import com.haulmont.cuba.core.sys.SingleAppResourcePatternResolver;
import com.haulmont.cuba.web.sys.CubaApplicationServlet;
import com.haulmont.cuba.web.sys.CubaDispatcherServlet;
import com.haulmont.cuba.web.sys.CubaHttpFilter;
import com.haulmont.cuba.web.sys.WebAppContextLoader;
import com.haulmont.restapi.sys.CubaRestApiServlet;
import com.haulmont.restapi.sys.SingleAppRestApiServlet;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

import javax.servlet.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link AppContext} loader of the web application block packed in a WAR together with the middleware block.
 */
public class SingleAppWebContextLoader extends WebAppContextLoader {

    private Set<String> dependencyJars;
    private ServletContextListener webServletContextListener;

    protected static final String FRONT_CONTEXT_NAME = "front";

    /**
     * Invoked reflectively by {@link SingleAppWebServletListener}.
     *
     * @param jarNames JARs of the core block
     */
    @SuppressWarnings("unused")
    public void setJarNames(String jarNames) {
        dependencyJars = new HashSet<>(Splitter.on("\n").omitEmptyStrings().trimResults().splitToList(jarNames));
    }

    /**
     * Here we create servlets and filters manually, to make sure the classes would be loaded using necessary classloader.
     */
    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        super.contextInitialized(servletContextEvent);

        ServletContext servletContext = servletContextEvent.getServletContext();

        registerAppServlet(servletContext);

        registerDispatchServlet(servletContext);

        registerRestApiServlet(servletContext);

        registerCubaHttpFilter(servletContext);

        registerFrontAppServlet(servletContext);

        registerClassLoaderFilter(servletContext);

        initWebServletContextListener(servletContextEvent, servletContext);
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        super.contextDestroyed(servletContextEvent);
        if (webServletContextListener != null) {
            webServletContextListener.contextDestroyed(servletContextEvent);
        }
    }

    protected void registerAppServlet(ServletContext servletContext) {
        CubaApplicationServlet cubaServlet = new CubaApplicationServlet();
        cubaServlet.setClassLoader(Thread.currentThread().getContextClassLoader());
        try {
            cubaServlet.init(new CubaServletConfig("app_servlet", servletContext));
        } catch (ServletException e) {
            throw new RuntimeException("An error occurred while initializing app_servlet servlet", e);
        }
        ServletRegistration.Dynamic cubaServletReg = servletContext.addServlet("app_servlet", cubaServlet);
        cubaServletReg.setLoadOnStartup(0);
        cubaServletReg.setAsyncSupported(true);
        cubaServletReg.addMapping("/*");
    }

    protected void registerDispatchServlet(ServletContext servletContext) {
        CubaDispatcherServlet cubaDispatcherServlet = new SingleAppDispatcherServlet(dependencyJars);
        try {
            cubaDispatcherServlet.init(new CubaServletConfig("dispatcher", servletContext));
        } catch (ServletException e) {
            throw new RuntimeException("An error occurred while initializing dispatcher servlet", e);
        }
        ServletRegistration.Dynamic cubaDispatcherServletReg = servletContext.addServlet("dispatcher", cubaDispatcherServlet);
        cubaDispatcherServletReg.setLoadOnStartup(1);
        cubaDispatcherServletReg.addMapping("/dispatch/*");
    }

    protected void registerRestApiServlet(ServletContext servletContext) {
        CubaRestApiServlet cubaRestApiServlet = new SingleAppRestApiServlet(dependencyJars);
        try {
            cubaRestApiServlet.init(new CubaServletConfig("rest_api", servletContext));
        } catch (ServletException e) {
            throw new RuntimeException("An error occurred while initializing dispatcher servlet", e);
        }
        ServletRegistration.Dynamic cubaRestApiServletReg = servletContext.addServlet("rest_api", cubaRestApiServlet);
        cubaRestApiServletReg.setLoadOnStartup(2);
        cubaRestApiServletReg.addMapping("/rest/*");

        DelegatingFilterProxy restSpringSecurityFilterChain = new DelegatingFilterProxy();
        restSpringSecurityFilterChain.setContextAttribute("org.springframework.web.servlet.FrameworkServlet.CONTEXT.rest_api");
        restSpringSecurityFilterChain.setTargetBeanName("springSecurityFilterChain");

        FilterRegistration.Dynamic restSpringSecurityFilterChainReg =
                servletContext.addFilter("restSpringSecurityFilterChain", restSpringSecurityFilterChain);
        restSpringSecurityFilterChainReg.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/rest/*");
    }

    protected void registerFrontAppServlet(ServletContext servletContext) {
        boolean hasFrontApp = false;
        try {
            hasFrontApp = servletContext.getResource("/" + FRONT_CONTEXT_NAME) != null;
        } catch (MalformedURLException e) {
            //Do nothing
        }
        if (hasFrontApp) {
            String contextPath = servletContext.getContextPath();
            String baseUrl = System.getProperty("cuba.front.baseUrl");
            if (baseUrl == null || baseUrl.length() == 0) {
                String path = "/" + FRONT_CONTEXT_NAME + "/";
                System.setProperty("cuba.front.baseUrl", "/".equals(contextPath) ? path : contextPath + path);
            }
            String apiUrl = System.getProperty("cuba.front.apiUrl");
            if (apiUrl == null || apiUrl.length() == 0) {
                String path = "/rest/";
                System.setProperty("cuba.front.apiUrl", "/".equals(contextPath) ? path : contextPath + path);
            }
            DispatcherServlet frontServlet;
            try {
                Class frontServletClass = ReflectionHelper.getClass("com.haulmont.cuba.web.sys.AppFrontServlet");
                frontServlet = (DispatcherServlet) ReflectionHelper.newInstance(frontServletClass,
                        FRONT_CONTEXT_NAME, (Supplier<ApplicationContext>) AppContext::getApplicationContext);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Unable to instantiate app front servlet", e);
            }
            ServletRegistration.Dynamic cubaServletReg = servletContext.addServlet("app_front_servlet", frontServlet);
            cubaServletReg.setLoadOnStartup(3);
            cubaServletReg.setAsyncSupported(true);
            cubaServletReg.addMapping(String.format("/%s/*", FRONT_CONTEXT_NAME));
        }
    }

    protected void registerCubaHttpFilter(ServletContext servletContext) {
        CubaHttpFilter cubaHttpFilter = new CubaHttpFilter();
        FilterRegistration.Dynamic cubaHttpFilterReg = servletContext.addFilter("CubaHttpFilter", cubaHttpFilter);
        cubaHttpFilterReg.setAsyncSupported(true);
        cubaHttpFilterReg.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    }

    protected void registerClassLoaderFilter(ServletContext servletContext) {
        FilterRegistration.Dynamic filterReg = servletContext.addFilter("WebSingleWarHttpFilter", new SetClassLoaderFilter());
        filterReg.setAsyncSupported(true);
        filterReg.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
        filterReg.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/dispatch/*");
        filterReg.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/rest/*");
    }

    @Override
    protected ClassPathXmlApplicationContext createApplicationContext(String[] locations) {
        return new CubaClassPathXmlApplicationContext(locations) {
            /**
             * Here we create resource resolver which scans only web jars (and avoid putting core beans to web context)
             * JAR_DEPENDENCIES properties is filled by com.haulmont.cuba.web.sys.singleapp.SingleAppWebServletListener
             * during application initialization.
             */
            @Override
            protected ResourcePatternResolver getResourcePatternResolver() {
                if (dependencyJars == null || dependencyJars.isEmpty()) {
                    throw new RuntimeException("No JARs defined for the 'web' block. " +
                            "Please check that web.dependencies file exists in WEB-INF directory.");
                }
                return new SingleAppResourcePatternResolver(this, dependencyJars);
            }
        };
    }

    @Override
    protected String getAppPropertiesConfig(ServletContext sc) {
        return sc.getInitParameter("appPropertiesConfigWeb");
    }

    protected void initWebServletContextListener(ServletContextEvent servletContextEvent, ServletContext sc) {
        String className = sc.getInitParameter("webServletContextListener");
        if (!Strings.isNullOrEmpty(className)) {
            try {
                Class<?> clazz = this.getClass().getClassLoader().loadClass(className);
                webServletContextListener = (ServletContextListener) clazz.newInstance();
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
                throw new RuntimeException("An error occurred while starting single WAR application", e);
            }
            webServletContextListener.contextInitialized(servletContextEvent);
        }
    }

    protected static class SetClassLoaderFilter implements Filter {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            //do nothing
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
            //do nothing
        }
    }
}