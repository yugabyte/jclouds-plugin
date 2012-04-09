package jenkins.plugins.jclouds.compute;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.inject.Module;
import hudson.Extension;
import hudson.Util;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;
import org.jclouds.Constants;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.ComputeServiceContextFactory;
import org.jclouds.compute.util.ComputeServiceUtils;
import org.jclouds.crypto.SshKeys;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.annotation.Nullable;
import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The JClouds version of the Jenkins Cloud.
 *
 * @author Vijay Kiran
 */
public class JCloudsCloud extends Cloud {

   private static final Logger LOGGER = Logger.getLogger(JCloudsCloud.class.getName());

   public final String identity;
   public final String credential;
   public final String providerName;

   public final String privateKey;
   public final String publicKey;
   public final String endPointUrl;
   public final String profile;
   public int instanceCap;
   public final List<JCloudsSlaveTemplate> templates;
   private transient ComputeService compute;

   public static JCloudsCloud get() {
      return Hudson.getInstance().clouds.get(JCloudsCloud.class);
   }
    
    @DataBoundConstructor
    public JCloudsCloud(final String profile,
                        final String providerName,
                        final String identity,
                        final String credential,
                        final String privateKey,
                        final String publicKey,
                        final String endPointUrl,
                        final int instanceCap,
                        final List<JCloudsSlaveTemplate> templates) {
        super(Util.fixEmptyAndTrim(profile));
        this.profile = Util.fixEmptyAndTrim(profile);
        this.providerName = Util.fixEmptyAndTrim(providerName);
        this.identity = Util.fixEmptyAndTrim(identity);
        this.credential = Util.fixEmptyAndTrim(credential);
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
        this.instanceCap = instanceCap;
        this.templates = Objects.firstNonNull(templates, Collections.<JCloudsSlaveTemplate>emptyList());
        readResolve();
    }


    protected Object readResolve() {
      for (JCloudsSlaveTemplate template : templates)
         template.cloud = this;
      return this;
   }

   public ComputeService getCompute() {
      if (this.compute == null) {
         Properties overrides = new Properties();
         if (!Strings.isNullOrEmpty(this.endPointUrl)) {
            overrides.setProperty(Constants.PROPERTY_ENDPOINT, this.endPointUrl);
         }
         Iterable<Module> modules = ImmutableSet.<Module>of(new SshjSshClientModule(), new SLF4JLoggingModule(),
               new EnterpriseConfigurationModule());
         this.compute = new ComputeServiceContextFactory()
               .createContext(this.providerName, this.identity, this.credential, modules, overrides).getComputeService();
      }
      return compute;
   }

   public List<JCloudsSlaveTemplate> getTemplates() {
      return Collections.unmodifiableList(templates);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
      throw new UnsupportedOperationException("Auto Provisioning Not implemented yet.");
   }

   @Override
   public boolean canProvision(final Label label) {
      return getTemplate(label) != null;
   }


   public JCloudsSlaveTemplate getTemplate(String name) {
      for (JCloudsSlaveTemplate t : templates)
         if (t.name.equals(name))
            return t;
      return null;
   }

   /**
    * Gets {@link jenkins.plugins.jclouds.compute.JCloudsSlaveTemplate} that has the matching {@link Label}.
    */
   public JCloudsSlaveTemplate getTemplate(Label label) {
      for (JCloudsSlaveTemplate t : templates)
         if (label == null || label.matches(t.getLabelSet()))
            return t;
      return null;
   }

   /**
    * Provisions a new node manually (by clicking a button in the computer list)
    * @param req {@link StaplerRequest}
    * @param rsp {@link StaplerResponse}
    * @param name Name of the template to provision
    * @throws ServletException
    * @throws IOException
    * @throws Descriptor.FormException
    */
   public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String name) throws ServletException, IOException, Descriptor.FormException {
      checkPermission(PROVISION);
      if (name == null) {
         sendError("The slave template name query parameter is missing", req, rsp);
         return;
      }
      JCloudsSlaveTemplate t = getTemplate(name);
      if (t == null) {
         sendError("No such slave template with name : " + name, req, rsp);
         return;
      }

      Collection<Node> jcloudsNodes = Collections2.filter(Jenkins.getInstance().getNodes(), new Predicate<Node>() {
         public boolean apply(@Nullable Node node) {
            //TODO Need to check if the node status should be taken into consideration for determining the instace cap
             return node != null && JCloudsSlave.class.isInstance(node) && ((JCloudsSlave)node).getCloudName().equals(profile);
         }
      });

      if (jcloudsNodes.size() < instanceCap) {
         StringWriter sw = new StringWriter();
         StreamTaskListener listener = new StreamTaskListener(sw);
         JCloudsSlave node = t.provision(listener);
         Hudson.getInstance().addNode(node);
         rsp.sendRedirect2(req.getContextPath() + "/computer/" + node.getNodeName());
      } else {
         sendError("Instance cap for this cloud is now reached for cloud profile: " + profile
               + " for template type " + name, req, rsp);
      }
   }


   @Extension
   public static class DescriptorImpl extends Descriptor<Cloud> {

      /**
       * Human readable name of this kind of configurable object.
       */
      @Override
      public String getDisplayName() {
         return "Cloud (JClouds)";
      }

      public FormValidation doTestConnection(@QueryParameter String providerName,
                                             @QueryParameter String identity,
                                             @QueryParameter String credential,
                                             @QueryParameter String privateKey,
                                             @QueryParameter String endPointUrl) {
         if (identity == null)
            return FormValidation.error("Invalid (AccessId).");
         if (credential == null)
            return FormValidation.error("Invalid credential (secret key).");
         if (privateKey == null)
            return FormValidation.error("Private key is not specified. Click 'Generate Key' to generate one.");


         // Remove empty text/whitespace from the fields.
         providerName = Util.fixEmptyAndTrim(providerName);
         identity = Util.fixEmptyAndTrim(identity);
         credential = Util.fixEmptyAndTrim(credential);
         endPointUrl = Util.fixEmptyAndTrim(endPointUrl);

         Iterable<Module> modules = ImmutableSet.<Module>of(new SshjSshClientModule(), new SLF4JLoggingModule(),
               new EnterpriseConfigurationModule());
         FormValidation result = FormValidation.ok("Connection succeeded!");
         ComputeService computeService = null;
         try {
            Properties overrides = new Properties();
            if (!Strings.isNullOrEmpty(endPointUrl)) {
               overrides.setProperty(Constants.PROPERTY_ENDPOINT, endPointUrl);
            }

            ComputeServiceContext context = new ComputeServiceContextFactory()
                  .createContext(providerName, identity, credential, modules, overrides);

            computeService = context.getComputeService();
            computeService.listNodes();
         } catch (Exception ex) {
            result = FormValidation.error("Cannot connect to specified cloud, please check the identity and credentials: " + ex.getMessage());
         } finally {
            if (computeService != null) {
               computeService.getContext().close();
            }
         }
         return result;
      }


      public FormValidation doGenerateKeyPair(StaplerResponse rsp, String identity, String credential) throws IOException, ServletException {
         Map<String, String> keyPair = SshKeys.generate();
         rsp.addHeader("script", "findPreviousFormItem(button,'privateKey').value='" + keyPair.get("private").replace("\n", "\\n") + "';" +
               "findPreviousFormItem(button,'publicKey').value='" + keyPair.get("public").replace("\n", "\\n") + "';"
         );
         return FormValidation.ok("Successfully generated private Key!");
      }

      public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException, ServletException {
         boolean hasStart = false, hasEnd = false;
         BufferedReader br = new BufferedReader(new StringReader(value));
         String line;
         while ((line = br.readLine()) != null) {
            if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
               hasStart = true;
            if (line.equals("-----END RSA PRIVATE KEY-----"))
               hasEnd = true;
         }
         if (!hasStart)
            return FormValidation.error("Please make sure that the private key starts with '-----BEGIN RSA PRIVATE KEY-----'");
         if (!hasEnd)
            return FormValidation.error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
         if (SshKeys.fingerprintPrivateKey(value) == null)
            return FormValidation.error("Invalid private key, please check again or click on 'Generate Key' to generate a new key");
         return FormValidation.ok();
      }

      public AutoCompletionCandidates doAutoCompleteProviderName(@QueryParameter final String value) {

         Iterable<String> supportedProviders = ComputeServiceUtils.getSupportedProviders();

         Iterable<String> matchedProviders = Iterables.filter(supportedProviders, new Predicate<String>() {
            public boolean apply(@Nullable String input) {
               return input != null && input.startsWith(value.toLowerCase());
            }
         });

         AutoCompletionCandidates candidates = new AutoCompletionCandidates();
         for (String matchedProvider : matchedProviders) {
            candidates.add(matchedProvider);
         }
         return candidates;
      }

      public FormValidation doCheckProfile(@QueryParameter String value) {
         return FormValidation.validateRequired(value);
      }

      public FormValidation doCheckProviderName(@QueryParameter String value) {
         return FormValidation.validateRequired(value);
      }

      public FormValidation doCheckPublicKey(@QueryParameter String value) {
         return FormValidation.validateRequired(value);
      }

      public FormValidation doCheckCredential(@QueryParameter String value) {
         return FormValidation.validateRequired(value);
      }

      public FormValidation doCheckIdentity(@QueryParameter String value) {
         return FormValidation.validateRequired(value);
      }

      public FormValidation doCheckInstanceCap(@QueryParameter String value) {
         return FormValidation.validatePositiveInteger(value);
      }

      public FormValidation doCheckEndPointUrl(@QueryParameter String value) {
         if (!value.isEmpty() && !value.startsWith("http")) {
            return FormValidation.error("The endpoint must be an URL");
         }
         return FormValidation.ok();
      }
   }
}
