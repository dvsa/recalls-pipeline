// using declarative pipeline: https://jenkins.io/doc/book/pipeline/syntax/
@Library('PipelineUtils@feature-BL-8651-build-pipeline-fe-lambda')
import dvsa.aws.mot.jenkins.pipeline.common.AWSFunctions
import dvsa.aws.mot.jenkins.pipeline.common.RepoFunctions
import dvsa.aws.mot.jenkins.pipeline.common.GlobalValues
import dvsa.aws.mot.jenkins.pipeline.common.UtilFunctions

// global variables
Object awsFunctionsFactory = new AWSFunctions()
Object repoFunctionsFactory = new RepoFunctions()
Object globalValuesFactory = new GlobalValues()
Object utilFunctionsFactory = new UtilFunctions()
Map<String, Map<String, String>> gitlab = globalValuesFactory.GITLAB_REPOS()
Map<String, Map<String, String>> github = globalValuesFactory.GITHUB_REPOS()

Map<String, String> params = [
    branch         : APP_BRANCH,
    tf_branch      : TF_BRANCH,
    action         : ACTION,
    environment    : ENVIRONMENT,
    release_version: RELEASE_VERSION,
]

String jenkinsCtrlNodeLabel = 'ctrl'
String jenkinsBuildLabel = 'cvr'
String account = 'dvsarecallsdev'
String group = 'dev'
String buildVersion = params.release_version + "_b" + env.BUILD_NUMBER
String buildUser = ''
String project = 'cvr'
String bucketPrefix = 'terraformscaffold'
String s3Bucket = ''

utilFunctionsFactory.setArtifactRetentionPolicy('', '', '7', '')

def failure(String reason) {
  currentBuild.result = "FAILURE"
  error(reason)
}

Integer buildPackage(String directory, String buildStamp) {
  dir (directory) {
    return sh ( script: "npm install && npm run lint && npm test && zip -r ../cvr-${directory}-${buildStamp}.zip * --exclude /test", returnStatus: true)
  }
}

pipeline {
  agent none
  options {
    ansiColor('xterm')
    timestamps()
    timeout(time: 1, unit: 'HOURS')
  }
  stages {
    stage('TF: Deployment bucket') {
      failFast true
      agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
      steps {
        script {
          wrap([$class: 'BuildUser']) { buildUser = env.BUILD_USER }
          currentBuild.description = "CVR ${params.action} | Branch: ${params.branch} | Env: ${params.environment} | ${buildUser}"
          repoFunctionsFactory.checkoutGitRepo(gitlab.cvr_terraform.url, params.tf_branch, gitlab.cvr_terraform.name, globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID)
          dir(gitlab.cvr_terraform.name) {
            String output
            if (awsFunctionsFactory.terraformScaffold(
                project,
                params.environment,
                group,
                globalValuesFactory.AWS_REGION,
                '',
                'CVR_Deployment_bucket',
                env.BUILD_NUMBER,
                'deployment-bucket',
                bucketPrefix,
                params.action)) {

              failure('Failed to run TF Scaffold on deployment-bucket component')
            } else if (params.action == 'apply') {
                output = awsFunctionsFactory.terraformScaffoldOutput(
                  project,
                  params.environment,
                  group,
                  globalValuesFactory.AWS_REGION,
                  '',
                  'CVR_Deployment_bucket_output',
                  env.BUILD_NUMBER,
                  'deployment-bucket',
                  bucketPrefix)

              s3Bucket = output.find(~/lambda_s3_bucket_id = (\S+)(?:$|\s)/) {match ->
                if (!match) {
                  failure('lambda_s3_bucket_id S3 bucket cannot be found in TF outputs')
                  return null
                }

                return match[1]
              }

              println "s3 deployment bucket saved: ${s3Bucket}"
            } //if
          } //dir
        } //script
      } //steps
    } //stage
    stage ("Build") {
      when  { expression { params.action != 'destroy' }}
      failFast true
      parallel {
        stage("Build: CVR frontend") {
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              repoFunctionsFactory.checkoutGitRepo(github.cvr_app.url, params.branch, github.cvr_app.name, globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID)
              dir(github.cvr_app.name) {
                if (buildPackage('frontend', buildVersion)) {
                  failure('Failed to build CVR frontend')
                } //if
              } //dir
            } //script
          } //steps
        } //stage
      } //parallel
    } //stage
    stage ("Deploy") {
      failFast true
      stages {
        stage("Deploy: Upload CVR frontend package") {
          when  { expression { params.action == 'apply' }}
          agent { node { label "${jenkinsBuildLabel} && ${account}" } }
          steps {
            script {
              dir(github.cvr_app.name) {
                String distFile = sh(script: "find . -type f -name \'*-frontend-${buildVersion}.zip\'", returnStdout: true).trim()
                println "Found build file: " + distFile
                awsFunctionsFactory.copyFilesToS3(s3Bucket, '', distFile)
                deleteDir()
                // fakeSmmtDist = distFile.substring(distFile.lastIndexOf("/")).replaceAll('/', '')
              } //dir
            } //script
          } //steps
        } //stage
        stage("Deploy: Terraform CVR") {
          agent { node { label "${jenkinsCtrlNodeLabel} && ${account}" } }
          steps {
            script {
              repoFunctionsFactory.checkoutGitRepo(gitlab.cvr_terraform.url, params.tf_branch, gitlab.cvr_terraform.name, globalValuesFactory.SSH_DEPLOY_GIT_CREDS_ID)
              dir(gitlab.cvr_terraform.name) {
                String tfLambdaParams =  "-var lambda_build_number=${buildVersion}".toString()
                if (awsFunctionsFactory.terraformScaffold(
                    project,
                    params.environment,
                    group,
                    globalValuesFactory.AWS_REGION,
                    tfLambdaParams,
                    'CVR_Deployment_bucket',
                    env.BUILD_NUMBER,
                    'cvr',
                    bucketPrefix,
                    params.action)) {

                  failure('Failed to run TF Scaffold on cvr component')
                } //if
              } //dir
            } //script
          } //steps
        } //stage

      } //parallel
    } //stage

  } //stages
} //pipeline