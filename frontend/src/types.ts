export type DeploymentStatus =
  | 'QUEUED'
  | 'CLONING'
  | 'BUILDING'
  | 'DEPLOYING'
  | 'HEALTHY'
  | 'FAILED'
  | 'CANCELLED'
  | 'STOPPED';

export interface App {
  id: number;
  name: string;
  gitUrl: string;
  defaultBranch: string;
  dockerfilePath?: string;
  targetPort?: number;
  allocatedPort?: number;
  activeContainerId?: string;
  createdAt?: string;
  latestDeployment?: Deployment;
  running?: boolean;
}

export interface Deployment {
  id: number;
  appId?: number;
  appName?: string;
  status: DeploymentStatus;
  commitHash?: string;
  imageTag?: string;
  containerId?: string;
  port?: number;
  startedAt?: string;
  finishedAt?: string;
}

export interface CreateAppForm {
  name: string;
  gitUrl: string;
  defaultBranch: string;
  dockerfilePath: string;
  targetPort: number;
}

